package com.lianyu.web.agent;

import com.lianyu.service.agent.AgentTextPort;
import com.lianyu.service.ai.ApiKeyVaultService;
import com.lianyu.service.dto.VaultEntryResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * {@link AgentTextPort} 实现：解析用户 vault → 调 Python `/agent/text/complete`。
 *
 * <p>任何异常都返回 null，让调用方回退 Java 原链路（与其它 agent 链路一致）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentTextAdapter implements AgentTextPort {

    private final AgentRuntimeClient client;
    private final ApiKeyVaultService vaultService;

    @Override
    public String complete(long userId,
                           String systemPrompt,
                           String userText,
                           String provider,
                           String model,
                           double temperature) {
        if (!client.properties().agentActive()) {
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
            apiKey = vault.getId() != null
                    ? vaultService.decryptKeyForChat(vault.getId())
                    : vault.getApiKey();
            resolvedModel = model != null && !model.isBlank() ? model.trim() : vault.getModelDefault();
        } catch (Exception e) {
            log.warn("Agent text: vault resolve failed, fallback to Java: {}", e.toString());
            return null;
        }
        if (baseUrl == null || baseUrl.isBlank() || apiKey == null || apiKey.isBlank()) {
            return null;
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userId", userId);
        body.put("system", systemPrompt == null ? "" : systemPrompt);
        body.put("userText", userText == null ? "" : userText);
        body.put("textBaseUrl", baseUrl);
        body.put("textApiKey", apiKey);
        body.put("textModel", resolvedModel);
        body.put("provider", provider == null ? "" : provider);
        body.put("temperature", temperature);

        try {
            String response = client.postJson("/agent/text/complete", userId, body);
            if (response == null) {
                return null;
            }
            com.fasterxml.jackson.databind.JsonNode root = client.readTree(response);
            if (root.hasNonNull("error")) {
                log.info("Agent text turn error {} (fallback to Java)", root.path("error").asText());
                return null;
            }
            com.fasterxml.jackson.databind.JsonNode content = root.path("content");
            if (content.isMissingNode() || content.isNull() || content.asText().isBlank()) {
                return null;
            }
            return content.asText();
        } catch (Exception e) {
            log.warn("Agent text turn failed (fallback to Java): {}", e.toString());
            return null;
        }
    }
}
