package com.lianyu.web.agent;

import com.lianyu.service.ai.AiChatService;
import com.lianyu.service.ai.ApiKeyVaultService;
import com.lianyu.service.storage.FileStorageService;
import com.lianyu.service.ai.SseChatStreamHelper;
import com.lianyu.service.dto.MessageDto;
import com.lianyu.service.dto.VaultEntryResponse;
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
 * <p>语义与 Java `VisionChatService.chatImageStream` 一致：**不是逐 token 推流**，
 * 而是阻塞完成「识图 + 文本」后一次性下发整段内容，再由
 * {@link SseChatStreamHelper} 发 pieces 与 [DONE]。
 *
 * <p>取图留在 Java：用 {@link FileStorageService} 从对象存储读字节转 base64，
 * Python 只收 base64，不引入新的 URL 下载与 SSRF 面。
 *
 * <p>返回 null 表示未接管（非 AGENT 模式 / 无语义凭据 / 取图失败 / Python 报错），
 * 调用方回退 `VisionChatService`。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentImageAdapter implements com.lianyu.service.agent.AgentImagePort {

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
    public SseEmitter streamIfActive(long userId,
                                     String systemPrompt,
                                     List<MessageDto> messages,
                                     String imageUrl,
                                     String provider,
                                     String model,
                                     int maxTokens,
                                     AiChatService.StreamCallback callback) {
        if (!client.properties().agentActive()) {
            return null;
        }
        byte[] imageBytes;
        String contentType;
        try {
            String objectKey = FileStorageService.extractObjectKey(imageUrl);
            if (objectKey == null) {
                log.debug("Agent image: invalid image url, fallback");
                return null;
            }
            imageBytes = fileStorageService.readObjectBytes(objectKey);
            contentType = fileStorageService.resolveContentType(objectKey);
            if (imageBytes == null || imageBytes.length == 0) {
                return null;
            }
        } catch (Exception e) {
            log.warn("Agent image: read object failed, fallback to Java: {}", e.toString());
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
            log.warn("Agent image: vault resolve failed, fallback: {}", e.toString());
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

        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        // 阻塞调用放到弹性线程池，避免占用 Tomcat 请求线程
        reactor.core.scheduler.Schedulers.boundedElastic().schedule(() -> {
            String response = client.postJson("/agent/chat-image", userId, body);
            if (response == null) {
                sseHelper.finishSseError(emitter, "图片消息发送失败，请稍后再试", "", callback);
                return;
            }
            com.fasterxml.jackson.databind.JsonNode root = client.readTree(response);
            if (root.hasNonNull("error")) {
                log.info("Agent image error {} (fallback not possible, already streaming)",
                        root.path("error").asText());
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
                userId, imageBytes.length, resolvedModel);
        return emitter;
    }
}
