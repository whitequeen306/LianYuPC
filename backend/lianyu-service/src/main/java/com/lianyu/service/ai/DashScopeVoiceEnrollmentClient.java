package com.lianyu.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lianyu.common.base.ErrorCode;
import com.lianyu.common.exception.BusinessException;
import com.lianyu.service.voice.DashScopeCloudEndpointValidator;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.Locale;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * DashScope custom voice enrollment (create/delete) using the caller's API key + base URL.
 */
@Slf4j
@Component
public class DashScopeVoiceEnrollmentClient {

    /** @deprecated use {@link DashScopeCloudEndpointValidator#RECOMMENDED_HTTP_MODEL} */
    public static final String HTTP_MODEL = DashScopeCloudEndpointValidator.RECOMMENDED_HTTP_MODEL;
    /** @deprecated use {@link DashScopeCloudEndpointValidator#RECOMMENDED_REALTIME_MODEL} */
    public static final String REALTIME_MODEL = DashScopeCloudEndpointValidator.RECOMMENDED_REALTIME_MODEL;

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public DashScopeVoiceEnrollmentClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    public String createVoice(String apiKey, String apiBaseUrl, String targetModel, String preferredName,
                              byte[] audioBytes, String mimeType, String language) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "请提供 API Key");
        }
        String enrollUrl = DashScopeCloudEndpointValidator.enrollUrl(apiBaseUrl);
        String mime = mimeType == null || mimeType.isBlank() ? "audio/wav" : mimeType;
        String dataUri = "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(audioBytes);
        try {
            ObjectNode input = objectMapper.createObjectNode();
            input.put("action", "create");
            input.put("target_model", targetModel);
            input.put("preferred_name", sanitizePreferredName(preferredName));
            input.put("language", language == null || language.isBlank() ? "zh" : language);
            ObjectNode audio = objectMapper.createObjectNode();
            audio.put("data", dataUri);
            input.set("audio", audio);

            ObjectNode body = objectMapper.createObjectNode();
            body.put("model", "qwen-voice-enrollment");
            body.set("input", input);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(enrollUrl))
                    .timeout(Duration.ofMinutes(3))
                    .header("Authorization", "Bearer " + apiKey.trim())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();
            HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode root = objectMapper.readTree(resp.body() == null ? "{}" : resp.body());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                String msg = root.path("message").asText(root.path("code").asText("报名失败"));
                log.warn("DashScope voice enroll failed status={} code={}",
                        resp.statusCode(), root.path("code").asText(""));
                throw new BusinessException(ErrorCode.AI_PROVIDER_ERROR, sanitizeUserMessage(msg));
            }
            String voice = root.path("output").path("voice").asText(null);
            if (voice == null || voice.isBlank()) {
                throw new BusinessException(ErrorCode.AI_PROVIDER_ERROR, "语音报名未返回 voice_id");
            }
            return voice;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("DashScope voice enroll error: {}", e.toString());
            throw new BusinessException(ErrorCode.AI_PROVIDER_ERROR, "语音报名失败，请检查 API Key、地址与音频后重试");
        }
    }

    public void deleteVoiceQuietly(String apiKey, String apiBaseUrl, String voiceId) {
        if (apiKey == null || apiKey.isBlank() || voiceId == null || voiceId.isBlank()) {
            return;
        }
        try {
            String enrollUrl = DashScopeCloudEndpointValidator.enrollUrl(
                    apiBaseUrl == null || apiBaseUrl.isBlank()
                            ? DashScopeCloudEndpointValidator.DEFAULT_BASE
                            : apiBaseUrl);
            ObjectNode input = objectMapper.createObjectNode();
            input.put("action", "delete");
            input.put("voice", voiceId);
            ObjectNode body = objectMapper.createObjectNode();
            body.put("model", "qwen-voice-enrollment");
            body.set("input", input);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(enrollUrl))
                    .timeout(Duration.ofSeconds(60))
                    .header("Authorization", "Bearer " + apiKey.trim())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();
            HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            log.info("DashScope voice delete voiceId={} status={}", voiceId, resp.statusCode());
        } catch (Exception e) {
            log.warn("DashScope voice delete ignored: {}", e.toString());
        }
    }

    /** Backward-compatible delete using default base. */
    public void deleteVoiceQuietly(String apiKey, String voiceId) {
        deleteVoiceQuietly(apiKey, DashScopeCloudEndpointValidator.DEFAULT_BASE, voiceId);
    }

    private static String sanitizePreferredName(String preferredName) {
        String base = preferredName == null ? "custom" : preferredName.trim().toLowerCase(Locale.ROOT);
        base = base.replaceAll("[^a-z0-9_]", "_");
        if (base.isBlank()) {
            base = "custom";
        }
        if (base.length() > 32) {
            base = base.substring(0, 32);
        }
        return base;
    }

    private static String sanitizeUserMessage(String msg) {
        if (msg == null || msg.isBlank()) {
            return "语音报名失败";
        }
        String m = msg.replaceAll("(?i)sk-[a-zA-Z0-9]{8,}", "[redacted]");
        if (m.length() > 160) {
            m = m.substring(0, 160);
        }
        return m;
    }
}
