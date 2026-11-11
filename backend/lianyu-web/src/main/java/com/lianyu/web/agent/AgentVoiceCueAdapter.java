package com.lianyu.web.agent;

import com.lianyu.service.agent.VoiceCuePort;
import com.lianyu.service.ai.ApiKeyVaultService;
import com.lianyu.service.dto.VaultEntryResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * {@link VoiceCuePort} 实现：解析用户 vault → 调 Python {@code /agent/voice/cue}。
 *
 * <p>与其它 agent 适配器一致：任何异常都返回 null（Java 静默跳过开场），
 * 不在这里拼任何话术。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentVoiceCueAdapter implements VoiceCuePort {

    private final AgentRuntimeClient client;
    private final ApiKeyVaultService vaultService;

    @Override
    public String generateCue(long userId,
                              Long characterId,
                              Long conversationId,
                              String characterName,
                              Map<String, Object> settings,
                              String kind) {
        if (!client.properties().agentActive()) {
            return null;
        }
        String baseUrl;
        String apiKey;
        String model;
        try {
            VaultEntryResponse vault = vaultService.resolvePreferredUserVault(userId);
            if (vault == null) {
                return null;
            }
            baseUrl = vault.getBaseUrl();
            apiKey = vault.getId() != null
                    ? vaultService.decryptKeyForChat(vault.getId())
                    : vault.getApiKey();
            model = vault.getModelDefault();
        } catch (Exception e) {
            log.warn("Voice cue: vault resolve failed: {}", e.toString());
            return null;
        }
        if (baseUrl == null || baseUrl.isBlank() || apiKey == null || apiKey.isBlank()) {
            return null;
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userId", userId);
        if (characterId != null) {
            body.put("characterId", characterId);
        }
        if (conversationId != null) {
            body.put("conversationId", conversationId);
        }
        body.put("characterName", characterName == null ? "" : characterName);
        body.put("settings", settings == null ? Map.of() : settings);
        body.put("kind", kind == null ? "opening" : kind);
        body.put("textBaseUrl", baseUrl);
        body.put("textApiKey", apiKey);
        body.put("textModel", model == null ? "" : model);

        try {
            String response = client.postJson("/agent/voice/cue", userId, body);
            if (response == null) {
                return null;
            }
            com.fasterxml.jackson.databind.JsonNode root = client.readTree(response);
            if (root.hasNonNull("error")) {
                log.info("Voice cue error {} (skip)", root.path("error").asText());
                return null;
            }
            String text = root.path("text").asText("");
            return text.isBlank() ? null : text;
        } catch (Exception e) {
            log.warn("Voice cue failed (skip): {}", e.toString());
            return null;
        }
    }
}
