package com.lianyu.web.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.lianyu.service.agent.CharacterGenerationPort;
import com.lianyu.service.ai.ApiKeyVaultService;
import com.lianyu.service.dto.VaultEntryResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 角色生成/抽取的 agent 实现：解密 vault 凭据 → 调 Python 端点。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentCharacterAdapter implements CharacterGenerationPort {

    private final AgentRuntimeClient client;
    private final ApiKeyVaultService vaultService;

    @Value("${spring.ai.openai.chat.options.model:}")
    private String defaultModel;

    @Override
    public Result generate(long userId, VaultEntryResponse vault, String description) {
        Credentials credentials = credentials(vault);
        if (credentials == null) {
            return null;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userId", userId);
        body.put("baseUrl", credentials.baseUrl());
        body.put("apiKey", credentials.apiKey());
        body.put("model", credentials.model());
        body.put("description", description == null ? "" : description);
        body.put("provider", vault.getProvider());
        return post("/agent/character/generate", userId, body);
    }

    @Override
    public Result importDraft(long userId, VaultEntryResponse vault,
                              String preparedSource, String userAddressing) {
        Credentials credentials = credentials(vault);
        if (credentials == null) {
            return null;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userId", userId);
        body.put("baseUrl", credentials.baseUrl());
        body.put("apiKey", credentials.apiKey());
        body.put("model", credentials.model());
        body.put("preparedSource", preparedSource == null ? "" : preparedSource);
        body.put("userAddressing", userAddressing == null ? "" : userAddressing);
        body.put("provider", vault.getProvider());
        return post("/agent/character/import", userId, body);
    }

    private Result post(String uri, long userId, Map<String, Object> body) {
        String response = client.postJson(uri, userId, body);
        if (response == null) {
            return null;
        }
        JsonNode root = client.readTree(response);
        if (root.hasNonNull("error")) {
            log.warn("Agent character endpoint {} error: {}", uri, root.path("error").asText());
            return null;
        }
        String content = root.path("content").asText("");
        if (content.isBlank()) {
            return null;
        }
        String model = root.path("model").asText(
                body.get("model") == null ? "" : String.valueOf(body.get("model")));
        return new Result(content, model);
    }

    private Credentials credentials(VaultEntryResponse vault) {
        if (!client.properties().agentActive() || vault == null) {
            return null;
        }
        try {
            String resolvedModel = vault.getModelDefault();
            if (resolvedModel == null || resolvedModel.isBlank()) {
                resolvedModel = defaultModel;
            }
            String apiKey = vault.getId() != null
                    ? vaultService.decryptKeyForChat(vault.getId())
                    : vault.getApiKey();
            if (vault.getBaseUrl() == null || vault.getBaseUrl().isBlank()
                    || apiKey == null || apiKey.isBlank()
                    || resolvedModel == null || resolvedModel.isBlank()) {
                return null;
            }
            return new Credentials(vault.getBaseUrl(), apiKey, resolvedModel);
        } catch (Exception e) {
            log.warn("Agent character: vault decrypt failed: {}", e.toString());
            return null;
        }
    }

    private record Credentials(String baseUrl, String apiKey, String model) {
    }
}
