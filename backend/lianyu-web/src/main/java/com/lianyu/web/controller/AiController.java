package com.lianyu.web.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.lianyu.common.base.Result;
import com.lianyu.service.agent.AgentBlockingPort;
import com.lianyu.service.agent.AgentChatResult;
import com.lianyu.service.agent.AgentStreamPort;
import com.lianyu.service.ai.AiChatQuotaService;
import com.lianyu.service.ai.ModelCatalogService;
import com.lianyu.service.ai.ApiKeyVaultService;
import com.lianyu.service.dto.AiChatRequest;
import com.lianyu.service.dto.ChatResult;
import com.lianyu.service.dto.CreateVaultRequest;
import com.lianyu.service.dto.ModelEntryDto;
import com.lianyu.service.dto.PreviewModelsRequest;
import com.lianyu.service.dto.UpdateVaultRequest;
import com.lianyu.service.dto.VaultEntryResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

@Tag(name = "AI", description = "AI 对话与 provider 管理")
@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
public class AiController {

    private final AgentStreamPort agentStreamPort;
    private final AgentBlockingPort agentBlockingPort;
    private final ModelCatalogService modelCatalogService;
    private final ApiKeyVaultService vaultService;
    private final AiChatQuotaService aiChatQuotaService;

    @Operation(summary = "流式对话（SSE）")
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@Valid @RequestBody AiChatRequest request) {
        long userId = StpUtil.getLoginIdAsLong();
        aiChatQuotaService.assertDirectChatAllowed(userId);
        String system = "";
        java.util.List<com.lianyu.service.dto.MessageDto> messages = request.getMessages();
        if (messages != null && !messages.isEmpty()
                && "system".equalsIgnoreCase(messages.get(0).getRole())) {
            system = messages.get(0).getContent();
            messages = messages.subList(1, messages.size());
        }
        SseEmitter emitter = agentStreamPort.streamIfActive(
                userId,
                system,
                messages,
                request.getProvider(),
                request.getModel(),
                request.getExpectedLanguage(),
                request.getChatToolCharacterId(),
                request.getMaxTokens() == null ? 1024 : request.getMaxTokens(),
                null);
        if (emitter == null) {
            throw new com.lianyu.common.exception.BusinessException(
                    com.lianyu.common.base.ErrorCode.AI_PROVIDER_ERROR, "消息发送失败，请稍后再试");
        }
        return emitter;
    }

    @Operation(summary = "非流式对话")
    @PostMapping("/chat")
    public Result<ChatResult> chat(@Valid @RequestBody AiChatRequest request) {
        long userId = StpUtil.getLoginIdAsLong();
        aiChatQuotaService.assertDirectChatAllowed(userId);
        AgentChatResult result = agentBlockingPort.complete(
                userId,
                request.getMessages(),
                request.getProvider(),
                request.getModel(),
                request.getTemperature(),
                request.getMaxTokens());
        if (result == null) {
            throw new com.lianyu.common.exception.BusinessException(
                    com.lianyu.common.base.ErrorCode.AI_PROVIDER_ERROR, "消息发送失败，请稍后再试");
        }
        return Result.ok(ChatResult.builder()
                .content(result.content())
                .promptTokens(result.promptTokens())
                .completionTokens(result.completionTokens())
                .totalTokens(result.totalTokens())
                .build());
    }

    @Operation(summary = "获取 provider 模型列表")
    @GetMapping("/models")
    public Result<List<ModelEntryDto>> models(@RequestParam String provider) {
        return Result.ok(modelCatalogService.fetchModels(StpUtil.getLoginIdAsLong(), provider));
    }

    @Operation(summary = "预览模型列表（未保存配置时）")
    @PostMapping("/models/preview")
    public Result<List<ModelEntryDto>> previewModels(@Valid @RequestBody PreviewModelsRequest request) {
        return Result.ok(modelCatalogService.previewModels(
                StpUtil.getLoginIdAsLong(), request.getBaseUrl(), request.getApiKey()));
    }

    @Operation(summary = "添加 API Key 配置")
    @PostMapping("/vault")
    public Result<VaultEntryResponse> createVault(@Valid @RequestBody CreateVaultRequest request) {
        return Result.ok(vaultService.create(StpUtil.getLoginIdAsLong(), request));
    }

    @Operation(summary = "列出所有 API Key 配置")
    @GetMapping("/vault")
    public Result<List<VaultEntryResponse>> listVault() {
        return Result.ok(vaultService.list(StpUtil.getLoginIdAsLong()));
    }

    @Operation(summary = "获取单个 API Key 配置")
    @GetMapping("/vault/{provider}")
    public Result<VaultEntryResponse> getVault(@PathVariable("provider") String provider) {
        return Result.ok(vaultService.get(StpUtil.getLoginIdAsLong(), provider));
    }

    @Operation(summary = "更新 API Key 配置")
    @PutMapping("/vault/{id}")
    public Result<VaultEntryResponse> updateVault(@PathVariable("id") Long id,
                                                  @RequestBody UpdateVaultRequest request) {
        return Result.ok(vaultService.update(StpUtil.getLoginIdAsLong(), id, request));
    }

    @Operation(summary = "删除 API Key 配置")
    @DeleteMapping("/vault/{id}")
    public Result<Void> deleteVault(@PathVariable("id") Long id) {
        vaultService.delete(StpUtil.getLoginIdAsLong(), id);
        return Result.ok();
    }
}
