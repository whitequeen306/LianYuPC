package com.lianyu.web.agent;

import com.lianyu.service.agent.AgentMemoryPort;
import com.lianyu.service.ai.ApiKeyVaultService;
import com.lianyu.service.dto.VaultEntryResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * {@link AgentMemoryPort} 实现：把最近消息交给 Python 的
 * `/agent/memory/extract-write`（抽取 + 过闸 + 写入 + outbox 入队）。
 *
 * <p>返回 false 的场合一律让 Java 走原链路：非 AGENT 模式、无 vault、
 * 凭据缺失、调用异常、Python 明确报错。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentMemoryAdapter implements AgentMemoryPort {

    private final AgentRuntimeClient client;
    private final ApiKeyVaultService vaultService;

    @Override
    public boolean extractAndWriteIfActive(long userId,
                                           Long conversationId,
                                           Long characterId,
                                           List<AgentMemoryMessage> messages,
                                           String provider,
                                           String model) {
        if (characterId == null || messages == null || messages.isEmpty()) {
            return false;
        }
        if (!client.properties().agentActive()) {
            return false;
        }

        String baseUrl;
        String apiKey;
        String resolvedModel;
        String resolvedProvider = provider;
        try {
            VaultEntryResponse vault = vaultService.resolveForChat(userId, provider);
            if (vault == null) {
                vault = vaultService.resolvePreferredUserVault(userId);
            }
            if (vault == null) {
                return false;
            }
            baseUrl = vault.getBaseUrl();
            apiKey = vault.getId() != null
                    ? vaultService.decryptKeyForChat(vault.getId())
                    : vault.getApiKey();
            resolvedModel = model != null && !model.isBlank() ? model.trim() : vault.getModelDefault();
            if (resolvedProvider == null || resolvedProvider.isBlank()) {
                resolvedProvider = vault.getProvider();
            }
        } catch (Exception e) {
            log.warn("Agent memory: vault resolve failed, fallback to Java: {}", e.toString());
            return false;
        }
        if (baseUrl == null || baseUrl.isBlank() || apiKey == null || apiKey.isBlank()
                || resolvedModel == null || resolvedModel.isBlank()) {
            return false;
        }

        List<Map<String, Object>> payloadMessages = new ArrayList<>();
        for (AgentMemoryMessage message : messages) {
            if (message == null || message.content() == null || message.content().isBlank()) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", message.id());
            item.put("role", message.role() == null ? "USER" : message.role().toUpperCase());
            item.put("content", message.content());
            payloadMessages.add(item);
        }
        if (payloadMessages.isEmpty()) {
            return false;
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userId", userId);
        body.put("characterId", characterId);
        // 会话 id 用于「上一幕结算」：闲置 >45 分钟的旧场景在这里补抽 episode。
        body.put("conversationId", conversationId);
        body.put("messages", payloadMessages);
        body.put("textBaseUrl", baseUrl);
        body.put("textApiKey", apiKey);
        body.put("textModel", resolvedModel);
        body.put("provider", resolvedProvider == null ? "" : resolvedProvider);

        String response = client.postJson("/agent/memory/extract-write", userId, body);
        if (response == null) {
            // 网络/后端失败：交回 Java，避免这段对话的记忆整体丢失
            return false;
        }
        com.fasterxml.jackson.databind.JsonNode root = client.readTree(response);
        if (root.hasNonNull("error")) {
            log.info("Agent memory extract-write error {} (fallback to Java)",
                    root.path("error").asText());
            return false;
        }
        log.info("Agent memory served: userId={}, characterId={}, extracted={}, created={}, updated={}",
                userId, characterId, root.path("extracted").asInt(0),
                root.path("created").asInt(0), root.path("updated").asInt(0));
        return true;
    }

    /**
     * 通话单轮：把用户原话与角色回复递给 Python，只写 episode。
     *
     * <p>与抽取链路分开：这里没有「回退 Java」的语义 —— 写不进去就当这一轮没记，
     * 绝不能因为记忆影响通话。凭据取用户默认 vault（通话没有 provider 上下文）。
     */
    @Override
    public boolean writeVoiceMomentIfActive(long userId,
                                            Long conversationId,
                                            Long characterId,
                                            String userText,
                                            String replyText) {
        if (characterId == null || userText == null || userText.isBlank()) {
            return false;
        }
        if (!client.properties().agentActive()) {
            return false;
        }

        String baseUrl;
        String apiKey;
        String model;
        try {
            VaultEntryResponse vault = vaultService.resolvePreferredUserVault(userId);
            if (vault == null) {
                return false;
            }
            baseUrl = vault.getBaseUrl();
            apiKey = vault.getId() != null
                    ? vaultService.decryptKeyForChat(vault.getId())
                    : vault.getApiKey();
            model = vault.getModelDefault();
        } catch (Exception e) {
            log.warn("Agent voice moment: vault resolve failed: {}", e.toString());
            return false;
        }
        if (baseUrl == null || baseUrl.isBlank() || apiKey == null || apiKey.isBlank()
                || model == null || model.isBlank()) {
            return false;
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userId", userId);
        body.put("characterId", characterId);
        body.put("conversationId", conversationId);
        body.put("userText", userText);
        body.put("replyText", replyText == null ? "" : replyText);
        body.put("textBaseUrl", baseUrl);
        body.put("textApiKey", apiKey);
        body.put("textModel", model);

        try {
            String response = client.postJson("/agent/memory/moment", userId, body);
            if (response == null) {
                return false;
            }
            com.fasterxml.jackson.databind.JsonNode root = client.readTree(response);
            if (root.path("written").asInt(0) > 0) {
                log.info("Voice moment written: userId={}, characterId={}, conv={}, episodeId={}",
                        userId, characterId, conversationId, root.path("episodeId").asInt(0));
                return true;
            }
            return false;
        } catch (Exception e) {
            log.warn("Agent voice moment failed (ignored): {}", e.toString());
            return false;
        }
    }
}
