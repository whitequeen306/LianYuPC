package com.lianyu.web.agent;

import com.lianyu.service.agent.AgentBlockingPort;
import com.lianyu.service.ai.ApiKeyVaultService;
import com.lianyu.service.dto.MessageDto;
import com.lianyu.service.dto.VaultEntryResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 阻塞对话交给 Python（群聊 / 语音 / 日记 / 摘要 / judge）。
 *
 * <p>凭据仍由 Java 解密（`ApiKeyVaultService`），随请求传给 agent ——
 * 这是「加解密留在 Java」这条 seam 的体现。
 *
 * <p>失败语义：任何异常/错误响应/空内容都返回 {@code null}，调用方直接报错。
 */
@Slf4j
@Component
public class AgentBlockingAdapter implements AgentBlockingPort {

    private final AgentRuntimeClient client;
    private final ApiKeyVaultService vaultService;

    @Autowired
    public AgentBlockingAdapter(AgentRuntimeClient client,
                                @Autowired(required = false) ApiKeyVaultService vaultService) {
        this.client = client;
        this.vaultService = vaultService;
    }

    @Override
    public com.lianyu.service.agent.AgentChatResult complete(
            long userId, List<MessageDto> messages, String provider, String model,
            Double temperature, Integer maxTokens) {
        if (vaultService == null || messages == null || messages.isEmpty()) {
            return null;
        }
        if (!reachable()) {
            return null;
        }
        String baseUrl;
        String apiKey;
        String resolvedModel;
        try {
            VaultEntryResponse vault = vaultService.resolveForChat(userId, provider);
            if (vault == null) {
                return null;
            }
            baseUrl = vault.getBaseUrl();
            apiKey = vault.getId() != null ? vaultService.decryptKeyForChat(vault.getId()) : null;
            resolvedModel = model != null && !model.isBlank() ? model.trim() : vault.getModelDefault();
        } catch (Exception e) {
            log.warn("Agent blocking: vault resolve failed, fallback: {}", e.toString());
            return null;
        }
        if (baseUrl == null || apiKey == null || resolvedModel == null) {
            return null;
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userId", userId);
        body.put("baseUrl", baseUrl);
        body.put("apiKey", apiKey);
        body.put("model", resolvedModel);
        body.put("messages", toPayload(messages));
        body.put("temperature", temperature == null ? 0.7 : temperature);
        body.put("maxTokens", maxTokens == null ? 1024 : maxTokens);
        body.put("provider", provider);

        try {
            String response = client.postJson("/agent/text/chat", userId, body);
            if (response == null) {
                return null;
            }
            com.fasterxml.jackson.databind.JsonNode root = client.readTree(response);
            if (root.hasNonNull("error")) {
                log.warn("Agent blocking error {} — fallback to Java", root.path("error").asText());
                return null;
            }
            String content = root.path("content").asText("");
            if (content.isBlank()) {
                return null;
            }
            log.debug("Agent blocking ok: chars={}", content.length());
            Integer promptTokens = intOrNull(root, "promptTokens");
            Integer completionTokens = intOrNull(root, "completionTokens");
            Integer totalTokens = intOrNull(root, "totalTokens");
            return new com.lianyu.service.agent.AgentChatResult(
                    content, promptTokens, completionTokens, totalTokens, null);
        } catch (Exception e) {
            log.warn("Agent blocking failed ({}), fallback to Java", e.toString());
            return null;
        }
    }

    static Integer intOrNull(com.fasterxml.jackson.databind.JsonNode root, String field) {
        com.fasterxml.jackson.databind.JsonNode node = root.path(field);
        return node.isMissingNode() || node.isNull() ? null : node.asInt();
    }

    static List<Map<String, String>> toPayload(List<MessageDto> messages) {
        List<Map<String, String>> payload = new ArrayList<>(messages.size());
        for (MessageDto message : messages) {
            if (message == null || message.getRole() == null) {
                continue;
            }
            Map<String, String> item = new LinkedHashMap<>();
            item.put("role", message.getRole());
            item.put("content", message.getContent() == null ? "" : message.getContent());
            payload.add(item);
        }
        return payload;
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
    }
}
