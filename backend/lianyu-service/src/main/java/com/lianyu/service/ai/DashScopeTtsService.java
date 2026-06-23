package com.lianyu.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class DashScopeTtsService {

    private static final String SYNTH_URL =
            "https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation";

    private final ObjectMapper objectMapper;
    private final PetVoiceRegistry petVoiceRegistry;

    @Value("${lianyu.ai.tts.enabled:true}")
    private boolean enabled;

    @Value("${lianyu.ai.tts.api-key:${lianyu.ai.vision.api-key:}}")
    private String apiKey;

    @Value("${lianyu.ai.tts.language-type:Chinese}")
    private String languageType;

    @Value("${lianyu.ai.tts.connect-timeout-ms:8000}")
    private int connectTimeoutMs;

    @Value("${lianyu.ai.tts.socket-timeout-ms:30000}")
    private int socketTimeoutMs;

    public DashScopeTtsService(ObjectMapper objectMapper, PetVoiceRegistry petVoiceRegistry) {
        this.objectMapper = objectMapper;
        this.petVoiceRegistry = petVoiceRegistry;
    }

    public record SynthesizedAudio(String mimeType, String base64) {}

    public SynthesizedAudio synthesizeForPet(String petId, String text) {
        if (!enabled) {
            log.info("Pet TTS skipped: disabled");
            return null;
        }
        if (text == null || text.isBlank()) {
            return null;
        }
        String voice = petVoiceRegistry.resolveVoiceId(petId);
        if (voice == null) {
            log.info("Pet TTS skipped: no voice mapping for petId={}", petId);
            return null;
        }
        String key = resolveApiKey();
        if (key == null || key.isBlank()) {
            log.warn("Pet TTS skipped: missing DASHSCOPE API key");
            return null;
        }
        try {
            String audioUrl = requestAudioUrl(key, petVoiceRegistry.getModel(), voice, text.trim());
            if (audioUrl == null || audioUrl.isBlank()) {
                return null;
            }
            byte[] bytes = downloadAudio(audioUrl);
            if (bytes == null || bytes.length == 0) {
                log.warn("Pet TTS audio download empty for petId={}", petId);
                return null;
            }
            log.info("Pet TTS ok: petId={}, bytes={}", petId, bytes.length);
            return new SynthesizedAudio(guessMimeType(audioUrl, bytes), Base64.getEncoder().encodeToString(bytes));
        } catch (Exception e) {
            log.warn("Pet TTS synthesis failed for pet {}: {}", petId, e.getMessage());
            return null;
        }
    }

    private String resolveApiKey() {
        if (apiKey != null && !apiKey.isBlank()) {
            return apiKey;
        }
        return System.getenv("DASHSCOPE_API_KEY");
    }

    private String requestAudioUrl(String key, String model, String voice, String text) throws Exception {
        ObjectNode input = objectMapper.createObjectNode();
        input.put("text", text);
        input.put("voice", voice);
        input.put("language_type", languageType);

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("model", model);
        payload.set("input", input);

        RequestConfig config = RequestConfig.custom()
                .setConnectTimeout(connectTimeoutMs)
                .setSocketTimeout(socketTimeoutMs)
                .build();

        try (CloseableHttpClient client = HttpClients.custom().setDefaultRequestConfig(config).build()) {
            HttpPost post = new HttpPost(SYNTH_URL);
            post.setHeader("Authorization", "Bearer " + key);
            post.setEntity(new StringEntity(objectMapper.writeValueAsString(payload), ContentType.APPLICATION_JSON));

            try (CloseableHttpResponse response = client.execute(post)) {
                int status = response.getStatusLine().getStatusCode();
                String body = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
                if (status < 200 || status >= 300) {
                    log.warn("Pet TTS synth HTTP {}: {}", status, body);
                    return null;
                }
                JsonNode root = objectMapper.readTree(body);
                JsonNode urlNode = root.path("output").path("audio").path("url");
                if (urlNode.isMissingNode() || urlNode.asText().isBlank()) {
                    log.warn("Pet TTS response missing audio url");
                    return null;
                }
                return urlNode.asText();
            }
        }
    }

    private byte[] downloadAudio(String audioUrl) throws Exception {
        RequestConfig config = RequestConfig.custom()
                .setConnectTimeout(connectTimeoutMs)
                .setSocketTimeout(socketTimeoutMs)
                .build();
        try (CloseableHttpClient client = HttpClients.custom().setDefaultRequestConfig(config).build()) {
            HttpGet get = new HttpGet(URI.create(audioUrl));
            try (CloseableHttpResponse response = client.execute(get)) {
                int status = response.getStatusLine().getStatusCode();
                if (status < 200 || status >= 300) {
                    log.warn("Pet TTS audio download HTTP {} for url={}", status, audioUrl);
                    return null;
                }
                try (InputStream in = response.getEntity().getContent()) {
                    return in.readAllBytes();
                }
            }
        }
    }

    private static String guessMimeType(String url, byte[] bytes) {
        String lower = url == null ? "" : url.toLowerCase();
        if (lower.contains(".mp3")) {
            return "audio/mpeg";
        }
        if (lower.contains(".wav") || (bytes.length > 12 && bytes[0] == 'R' && bytes[1] == 'I')) {
            return "audio/wav";
        }
        return "audio/wav";
    }
}
