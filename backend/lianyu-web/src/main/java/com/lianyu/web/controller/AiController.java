package com.lianyu.web.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.lianyu.common.base.Result;
import com.lianyu.service.AiChatQuotaService;
import com.lianyu.service.AiChatService;
import com.lianyu.service.ApiKeyVaultService;
import com.lianyu.service.dto.AiChatRequest;
import com.lianyu.service.dto.ChatResult;
import com.lianyu.service.dto.CreateVaultRequest;
import com.lianyu.service.dto.ModelEntryDto;
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

    private final AiChatService aiChatService;
    private final ApiKeyVaultService vaultService;
    private final AiChatQuotaService aiChatQuotaService;

    @Operation(summary = "流式对话（SSE）")
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@Valid @RequestBody AiChatRequest request) {
        long userId = StpUtil.getLoginIdAsLong();
        aiChatQuotaService.assertDirectChatAllowed(userId);
        return aiChatService.chatStream(userId, request);
    }

    @Operation(summary = "非流式对话")
    @PostMapping("/chat")
    public Result<ChatResult> chat(@Valid @RequestBody AiChatRequest request) {
        long userId = StpUtil.getLoginIdAsLong();
        aiChatQuotaService.assertDirectChatAllowed(userId);
        return Result.ok(aiChatService.chatBlocking(userId, request));
    }

    @Operation(summary = "获取 provider 模型列表")
    @GetMapping("/models")
    public Result<List<ModelEntryDto>> models(@RequestParam String provider) {
        return Result.ok(aiChatService.fetchModels(StpUtil.getLoginIdAsLong(), provider));
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
