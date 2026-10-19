package com.lianyu.web.agent;

import com.lianyu.service.agent.AgentStreamPort;
import com.lianyu.service.ai.AiChatService;
import com.lianyu.service.ai.ApiKeyVaultService;
import com.lianyu.service.dto.MessageDto;
import com.lianyu.service.dto.VaultEntryResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * {@link AgentStreamPort} 的 web 侧实现：解析用户 vault → 调 Python 流式端点 →
 * 经 {@link AgentStreamBridge} 增量转发给客户端。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentStreamAdapter implements AgentStreamPort {

    private static final long SSE_TIMEOUT_MS = 1_800_000L;

    /** 单聊链路在 Java 侧会挂的工具（与 ToolManager 默认集对齐；Python 已支持）。 */
    private static final List<String> CHAT_TOOLS = List.of(
            "get_current_time", "get_weather", "memory_search", "get_my_recent_life");

    private final AgentRuntimeClient client;
    private final AgentStreamBridge bridge;
    private final ApiKeyVaultService vaultService;

    @Override
    public SseEmitter streamIfActive(long userId,
                                     String systemPrompt,
                                     List<MessageDto> messages,
                                     String provider,
                                     String model,
                                     String expectedLanguage,
                                     Long characterId,
                                     int maxTokens,
                                     com.lianyu.service.agent.AgentStreamCallback callback) {
        // 前置健康检查：agent 不可用时立刻失败，避免「已发出流式请求后才失败」。
        // 结果缓存 3 秒，避免每条消息都探测。
        if (!reachable()) {
            return null;
        }
        String baseUrl;
        String apiKey;
        String resolvedModel;
        try {
            VaultEntryResponse vault = vaultService.resolveForChat(userId, provider);
            if (vault == null) {
                return null;  // 无用户文本模型 → 回退 Java（那边会给出准确错误）
            }
            baseUrl = vault.getBaseUrl();
            apiKey = vault.getId() != null
                    ? vaultService.decryptKeyForChat(vault.getId())
                    : vault.getApiKey();
            resolvedModel = model != null && !model.isBlank() ? model.trim() : vault.getModelDefault();
        } catch (Exception e) {
            log.warn("Agent stream: vault resolve failed, fallback to Java: {}", e.toString());
            return null;
        }
        if (baseUrl == null || baseUrl.isBlank() || apiKey == null || apiKey.isBlank()) {
            return null;
        }

        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        AgentStreamBridge.Relay relay = bridge.newRelay(emitter, callback);

        List<Map<String, String>> payloadMessages = new ArrayList<>();
        if (messages != null) {
            for (MessageDto dto : messages) {
                if (dto == null) {
                    continue;
                }
                // system 单独传（Java 已组装好），其余保持原序
                if ("system".equalsIgnoreCase(dto.getRole())) {
                    continue;
                }
                payloadMessages.add(Map.of(
                        "role", dto.getRole() == null ? "user" : dto.getRole().toLowerCase(),
                        "content", dto.getContent() == null ? "" : dto.getContent()));
            }
        }

        log.info("Agent stream dispatched: userId={}, messages={}, model={}", userId, payloadMessages.size(), resolvedModel);
        client.streamChat(userId, systemPrompt, payloadMessages, baseUrl, apiKey, resolvedModel,
                        provider, expectedLanguage, characterId, CHAT_TOOLS, 0.8, maxTokens)

                .subscribe(
                        relay::accept,
                        error -> {
                            log.warn("Agent stream upstream error: {}", error.toString());
                            relay.fail("消息发送失败，请稍后再试");
                        },
                        relay::finish);
        return emitter;
    }
    // ── 健康探测（3 秒缓存）──

    private volatile boolean healthy;
    private volatile long healthyCheckedAt;

    private boolean reachable() {
        long now = System.currentTimeMillis();
        if (now - healthyCheckedAt < 3_000L) {
            return healthy;
        }
        healthy = client.isHealthy();
        healthyCheckedAt = now;
        return healthy;
    }}
