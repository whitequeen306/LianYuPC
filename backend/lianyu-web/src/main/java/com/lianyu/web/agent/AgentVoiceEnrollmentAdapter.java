package com.lianyu.web.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.lianyu.common.base.ErrorCode;
import com.lianyu.common.exception.BusinessException;
import com.lianyu.service.agent.VoiceEnrollmentPort;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 音色注册交给 Python（DashScope 音色克隆）。
 *
 * <p>失败抛 {@code BusinessException(AI_PROVIDER_ERROR, message)}：
 * {@code message} 由 Python 侧脱敏（`sk-*` → `[redacted]`）并截断到 160 字符，
 * 可直接返回给前端。这里不再做二次加工，避免两边文案分叉。
 */
@Slf4j
@Component
public class AgentVoiceEnrollmentAdapter implements VoiceEnrollmentPort {

    private final AgentRuntimeClient client;

    public AgentVoiceEnrollmentAdapter(AgentRuntimeClient client) {
        this.client = client;
    }

    @Override
    public String createVoice(long userId,
                              String apiKey,
                              String apiBaseUrl,
                              String targetModel,
                              String preferredName,
                              byte[] audioBytes,
                              String mimeType,
                              String language) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userId", userId);
        body.put("apiKey", apiKey == null ? "" : apiKey);
        body.put("apiBaseUrl", apiBaseUrl == null ? "" : apiBaseUrl);
        body.put("targetModel", targetModel == null ? "" : targetModel);
        body.put("preferredName", preferredName == null ? "" : preferredName);
        body.put("audioBase64", audioBytes == null
                ? "" : Base64.getEncoder().encodeToString(audioBytes));
        body.put("mimeType", mimeType == null ? "audio/wav" : mimeType);
        body.put("language", language == null ? "zh" : language);

        String response = client.postJson("/agent/voice/enroll", userId, body);
        if (response == null) {
            throw new BusinessException(ErrorCode.AI_PROVIDER_ERROR, "语音报名服务不可用，请稍后再试");
        }
        JsonNode root = client.readTree(response);
        JsonNode error = root.path("error");
        if (!error.isMissingNode() && !error.isNull()) {
            String message = root.path("message").asText("");
            log.warn("Voice enroll agent error {}: {}", error.asText(), message);
            throw new BusinessException(ErrorCode.AI_PROVIDER_ERROR,
                    message == null || message.isBlank() ? "语音报名失败" : message);
        }
        String voice = root.path("voice").asText("");
        if (voice.isBlank()) {
            throw new BusinessException(ErrorCode.AI_PROVIDER_ERROR, "语音报名未返回 voice_id");
        }
        return voice;
    }

    @Override
    public void deleteVoiceQuietly(long userId, String apiKey, String apiBaseUrl, String voiceId) {
        if (apiKey == null || apiKey.isBlank() || voiceId == null || voiceId.isBlank()) {
            return;
        }
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("userId", userId);
            body.put("apiKey", apiKey);
            body.put("apiBaseUrl", apiBaseUrl == null ? "" : apiBaseUrl);
            body.put("voiceId", voiceId);
            client.postJson("/agent/voice/delete", userId, body);
        } catch (Exception e) {
            log.warn("Voice delete ignored voiceId={}: {}", voiceId, e.toString());
        }
    }
}
