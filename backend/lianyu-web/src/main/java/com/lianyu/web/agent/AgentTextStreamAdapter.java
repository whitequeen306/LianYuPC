package com.lianyu.web.agent;

import com.lianyu.common.base.ErrorCode;
import com.lianyu.common.exception.BusinessException;
import com.lianyu.service.agent.AgentTextStreamPort;
import com.lianyu.service.ai.ApiKeyVaultService;
import com.lianyu.service.dto.MessageDto;
import com.lianyu.service.dto.VaultEntryResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 语音通话 token 流：消费 Python `/agent/chat-stream` 并把增量交给 TTS。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentTextStreamAdapter implements AgentTextStreamPort {

    private final AgentRuntimeClient client;
    private final ApiKeyVaultService vaultService;

    @Override
    public CompletableFuture<String> stream(long userId,
                                            String systemPrompt,
                                            List<MessageDto> messages,
                                            String provider,
                                            String model,
                                            String expectedLanguage,
                                            Consumer<String> onDelta) {
        CompletableFuture<String> future = new CompletableFuture<>();
        if (!client.properties().agentActive()) {
            future.completeExceptionally(new BusinessException(ErrorCode.AI_PROVIDER_ERROR,
                    "对话服务不可用"));
            return future;
        }
        String baseUrl;
        String apiKey;
        String resolvedModel;
        try {
            VaultEntryResponse vault = vaultService.resolveForChat(userId, provider);
            if (vault == null) {
                future.completeExceptionally(new BusinessException(ErrorCode.AI_PROVIDER_ERROR,
                        "未配置文本模型，请在设置中添加"));
                return future;
            }
            baseUrl = vault.getBaseUrl();
            apiKey = vault.getId() != null
                    ? vaultService.decryptKeyForChat(vault.getId())
                    : vault.getApiKey();
            resolvedModel = model != null && !model.isBlank() ? model.trim() : vault.getModelDefault();
        } catch (Exception e) {
            log.warn("Agent text stream: vault resolve failed: {}", e.toString());
            future.completeExceptionally(e);
            return future;
        }
        if (baseUrl == null || baseUrl.isBlank() || apiKey == null || apiKey.isBlank()) {
            future.completeExceptionally(new BusinessException(ErrorCode.AI_PROVIDER_ERROR,
                    "未配置文本模型，请在设置中添加"));
            return future;
        }

        List<Map<String, String>> payload = new ArrayList<>();
        if (messages != null) {
            for (MessageDto dto : messages) {
                if (dto == null || "system".equalsIgnoreCase(dto.getRole())) {
                    continue;
                }
                payload.add(Map.of(
                        "role", dto.getRole() == null ? "user" : dto.getRole().toLowerCase(),
                        "content", dto.getContent() == null ? "" : dto.getContent()));
            }
        }

        StringBuilder content = new StringBuilder();
        client.streamChat(userId, systemPrompt, payload, baseUrl, apiKey, resolvedModel,
                        provider, expectedLanguage, null,
                        List.of("get_current_time", "get_weather", "memory_search", "get_my_recent_life"),
                        0.7, 256)
                .subscribe(chunk -> {
                    AgentSseParser.Event event = AgentSseParser.parseLine(chunk);
                    switch (event.type()) {
                        case CONTENT -> {
                            if (event.text() != null) {
                                content.append(event.text());
                                if (onDelta != null) {
                                    try {
                                        onDelta.accept(event.text());
                                    } catch (Exception e) {
                                        log.debug("onDelta failed: {}", e.toString());
                                    }
                                }
                            }
                        }
                        case REPLACE -> {
                            content.setLength(0);
                            content.append(event.text() == null ? "" : event.text());
                        }
                        case ERROR -> future.completeExceptionally(new BusinessException(
                                ErrorCode.AI_PROVIDER_ERROR, event.text()));
                        case DONE -> future.complete(content.toString());
                        case IGNORED -> {
                            // heartbeat / empty frame
                        }
                    }
                }, error -> {
                    if (!future.isDone()) {
                        future.completeExceptionally(error);
                    }
                }, () -> {
                    if (!future.isDone()) {
                        future.complete(content.toString());
                    }
                });
        return future;
    }
}
