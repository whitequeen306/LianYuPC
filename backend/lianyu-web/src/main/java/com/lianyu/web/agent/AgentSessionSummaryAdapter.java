package com.lianyu.web.agent;

import com.lianyu.service.agent.SessionSummaryPort;
import com.lianyu.service.ai.ApiKeyVaultService;
import com.lianyu.service.dto.VaultEntryResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 会话摘要交给 Python 生成（合并 / 压缩）。
 *
 * <p>摘要文案属于 AI 文本，必须只在 Python 存在；Java 侧保留的回退实现
 * 摘要文案与模型调用在 Python；Java 只负责产品规则与写入 Redis。
 */
@Slf4j
@Component
public class AgentSessionSummaryAdapter implements SessionSummaryPort {

    private final AgentRuntimeClient client;
    private final ApiKeyVaultService vaultService;

    @Autowired
    public AgentSessionSummaryAdapter(AgentRuntimeClient client,
                                      @Autowired(required = false) ApiKeyVaultService vaultService) {
        this.client = client;
        this.vaultService = vaultService;
    }

    @Override
    public String generate(long userId, String provider, String model, String mode,
                           String existingSummary, List<SummaryMessage> messages,
                           int targetChars) {
        if (vaultService == null) {
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
            log.warn("Session summary: vault resolve failed, fallback: {}", e.toString());
            return null;
        }
        if (baseUrl == null || apiKey == null || resolvedModel == null) {
            return null;
        }

        List<Map<String, String>> payload = new ArrayList<>();
        if (messages != null) {
            for (SummaryMessage message : messages) {
                if (message == null || message.content() == null || message.content().isBlank()) {
                    continue;
                }
                Map<String, String> item = new LinkedHashMap<>();
                item.put("role", message.role() == null ? "USER" : message.role());
                item.put("content", message.content());
                payload.add(item);
            }
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userId", userId);
        body.put("baseUrl", baseUrl);
        body.put("apiKey", apiKey);
        body.put("model", resolvedModel);
        body.put("mode", mode == null ? "merge" : mode);
        body.put("existingSummary", existingSummary == null ? "" : existingSummary);
        body.put("messages", payload);
        body.put("targetChars", targetChars);

        try {
            String response = client.postJson("/agent/session-summary", userId, body);
            if (response == null) {
                return null;
            }
            com.fasterxml.jackson.databind.JsonNode root = client.readTree(response);
            if (root.hasNonNull("error")) {
                log.warn("Session summary agent error {} — fallback", root.path("error").asText());
                return null;
            }
            String text = root.path("text").asText("");
            return text.isBlank() ? null : text;
        } catch (Exception e) {
            log.warn("Session summary agent failed ({}), fallback", e.toString());
            return null;
        }
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
