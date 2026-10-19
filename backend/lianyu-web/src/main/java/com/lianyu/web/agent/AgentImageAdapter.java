package com.lianyu.web.agent;

import com.lianyu.service.agent.AgentChatResult;
import com.lianyu.service.agent.AgentImagePort;
import com.lianyu.service.agent.AgentStreamCallback;
import com.lianyu.service.ai.ApiKeyVaultService;
import com.lianyu.service.ai.SseChatStreamHelper;
import com.lianyu.service.dto.MessageDto;
import com.lianyu.service.dto.VaultEntryResponse;
import com.lianyu.service.storage.FileStorageService;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 图片消息的 agent 路径。
 *
 * <p>取图仍在 Java（对象存储 → 字节 → base64），Python 只收 base64，
 * 不引入新的 URL 下载与 SSRF 面。OFF 退役后这里不再有 Java 模型回退。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentImageAdapter implements AgentImagePort {

    private static final long SSE_TIMEOUT_MS = 1_800_000L;

    private final AgentRuntimeClient client;
    private final ApiKeyVaultService vaultService;
    private final FileStorageService fileStorageService;
    private final SseChatStreamHelper sseHelper;

    @Value("${lianyu.ai.multimodal.base-url:}")
    private String visionBaseUrl;

    @Value("${lianyu.ai.multimodal.api-key:}")
    private String visionApiKey;

    @Value("${lianyu.ai.multimodal.model:}")
    private String visionModel;

    @Override
    public AgentChatResult completeIfActive(long userId,
                                            String systemPrompt,
                                            List<MessageDto> messages,
                                            String imageUrl,
                                            String provider,
                                            String model,
                                            int maxTokens) {
        Prepared prepared = prepare(userId, systemPrompt, messages, imageUrl, provider, model, maxTokens);
        if (prepared == null) {
            return null;
        }
        String response = client.postJson("/agent/chat-image", userId, prepared.body());
        if (response == null) {
            return null;
        }
        com.fasterxml.jackson.databind.JsonNode root = client.readTree(response);
        if (root.hasNonNull("error")) {
            log.warn("Agent image blocking error: {}", root.path("error").asText());
            return null;
        }
        String content = root.path("content").asText("");
        if (content.isBlank()) {
            return null;
        }
        return new AgentChatResult(
                content,
                intOrNull(root, "promptTokens"),
                intOrNull(root, "completionTokens"),
                intOrNull(root, "totalTokens"),
                root.path("imageDescription").asText(""));
    }

    @Override
    public SseEmitter streamIfActive(long userId,
                                     String systemPrompt,
                                     List<MessageDto> messages,
                                     String imageUrl,
                                     String provider,
                                     String model,
                                     int maxTokens,
                                     AgentStreamCallback callback) {
        Prepared prepared = prepare(userId, systemPrompt, messages, imageUrl, provider, model, maxTokens);
        if (prepared == null) {
            return null;
        }
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        reactor.core.scheduler.Schedulers.boundedElastic().schedule(() -> {
            String response = client.postJson("/agent/chat-image", userId, prepared.body());
            if (response == null) {
                sseHelper.finishSseError(emitter, "图片消息发送失败，请稍后再试", "", callback);
                return;
            }
            com.fasterxml.jackson.databind.JsonNode root = client.readTree(response);
            if (root.hasNonNull("error")) {
                log.info("Agent image error {} (no fallback)", root.path("error").asText());
                sseHelper.finishSseError(emitter, "图片识别失败，请换一张图片再试", "", callback);
                return;
            }
            String description = root.path("imageDescription").asText("");
            String content = root.path("content").asText("");
            if (callback != null && !description.isBlank()) {
                callback.onVisionComplete(description);
            }
            try {
                if (!content.isBlank()) {
                    sseHelper.sendSseChunk(emitter, content);
                }
            } catch (Exception e) {
                log.debug("Agent image: send chunk failed: {}", e.toString());
            }
            if (content.isBlank()) {
                sseHelper.finishSseError(emitter, "对方没有回复内容，请重试", "", callback);
                return;
            }
            sseHelper.finishSseSuccess(emitter, content, callback);
        });
        log.info("Agent image dispatched: userId={}, bytes={}, model={}",
                userId, prepared.bytes(), prepared.model());
        return emitter;
    }

    private Prepared prepare(long userId,
                             String systemPrompt,
                             List<MessageDto> messages,
                             String imageUrl,
                             String provider,
                             String model,
                             int maxTokens) {
        if (!client.properties().agentActive()) {
            return null;
        }
        byte[] imageBytes;
        try {
            String objectKey = FileStorageService.extractObjectKey(imageUrl);
            if (objectKey == null) {
                return null;
            }
            imageBytes = fileStorageService.readObjectBytes(objectKey);
            if (imageBytes == null || imageBytes.length == 0) {
                return null;
            }
        } catch (Exception e) {
            log.warn("Agent image: read object failed: {}", e.toString());
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
            apiKey = vault.getId() != null ? vaultService.decryptKeyForChat(vault.getId()) : vault.getApiKey();
            resolvedModel = model != null && !model.isBlank() ? model.trim() : vault.getModelDefault();
        } catch (Exception e) {
            log.warn("Agent image: vault resolve failed: {}", e.toString());
            return null;
        }
        if (baseUrl == null || baseUrl.isBlank() || apiKey == null || apiKey.isBlank()) {
            return null;
        }

        List<Map<String, String>> payloadMessages = new ArrayList<>();
        if (messages != null) {
            for (MessageDto dto : messages) {
                if (dto == null || dto.getRole() == null || "system".equalsIgnoreCase(dto.getRole())) {
                    continue;
                }
                payloadMessages.add(Map.of(
                        "role", dto.getRole().toLowerCase(),
                        "content", dto.getContent() == null ? "" : dto.getContent()));
            }
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userId", userId);
        body.put("imagePngBase64", Base64.getEncoder().encodeToString(imageBytes));
        body.put("system", systemPrompt == null ? "" : systemPrompt);
        body.put("messages", payloadMessages);
        body.put("textBaseUrl", baseUrl);
        body.put("textApiKey", apiKey);
        body.put("textModel", resolvedModel);
        body.put("visionBaseUrl", visionBaseUrl);
        body.put("visionApiKey", visionApiKey);
        body.put("visionModel", visionModel);
        body.put("provider", provider == null ? "" : provider);
        body.put("temperature", 0.8);
        body.put("maxTokens", maxTokens);
        return new Prepared(body, resolvedModel, imageBytes.length);
    }

    private record Prepared(Map<String, Object> body, String model, int bytes) {
    }

    private static Integer intOrNull(com.fasterxml.jackson.databind.JsonNode root, String field) {
        com.fasterxml.jackson.databind.JsonNode node = root.path(field);
        return node.isMissingNode() || node.isNull() ? null : node.asInt();
    }
}
