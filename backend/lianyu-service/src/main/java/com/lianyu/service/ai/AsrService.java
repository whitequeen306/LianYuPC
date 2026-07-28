package com.lianyu.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lianyu.common.base.ErrorCode;
import com.lianyu.common.exception.BusinessException;
import java.io.IOException;
import java.util.Locale;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.multipart.MultipartFile;

@Slf4j
@Service
@RequiredArgsConstructor
public class AsrService {

    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "audio/wav",
            "audio/x-wav",
            "audio/webm",
            "audio/ogg",
            "audio/opus",
            "audio/mpeg",
            "audio/mp4",
            "application/octet-stream"
    );

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            ".wav", ".webm", ".ogg", ".opus", ".mp3", ".m4a"
    );

    private final ObjectMapper objectMapper;
    private final RestClient.Builder restClientBuilder;

    @Value("${lianyu.asr.enabled:true}")
    private boolean enabled;

    @Value("${lianyu.asr.base-url:http://localhost:8081}")
    private String baseUrl;

    @Value("${lianyu.asr.max-bytes:8388608}")
    private long maxBytes;

    public String transcribe(MultipartFile file) {
        if (!enabled) {
            throw new BusinessException(ErrorCode.AI_PROVIDER_ERROR, "语音识别服务未启用");
        }
        validateUpload(file);
        String url = baseUrl.replaceAll("/+$", "") + "/transcribe";
        String originalName = file.getOriginalFilename() == null ? "audio.webm" : file.getOriginalFilename();
        try {
            byte[] bytes = file.getBytes();
            ByteArrayResource resource = new ByteArrayResource(bytes) {
                @Override
                public String getFilename() {
                    return originalName;
                }
            };
            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            HttpHeaders partHeaders = new HttpHeaders();
            partHeaders.setContentType(MediaType.APPLICATION_OCTET_STREAM);
            body.add("file", new HttpEntity<>(resource, partHeaders));

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);

            RestClient client = restClientBuilder.build();
            ResponseEntity<String> response = client.post()
                    .uri(url)
                    .headers(h -> h.addAll(headers))
                    .body(body)
                    .retrieve()
                    .toEntity(String.class);

            JsonNode root = objectMapper.readTree(response.getBody() == null ? "{}" : response.getBody());
            return root.path("text").asText("").trim();
        } catch (RestClientResponseException e) {
            log.warn("ASR upstream HTTP {}: {}", e.getStatusCode().value(), e.getResponseBodyAsString());
            throw new BusinessException(ErrorCode.AI_PROVIDER_ERROR, "语音识别失败，请稍后再试");
        } catch (BusinessException e) {
            throw e;
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "音频读取失败");
        } catch (Exception e) {
            log.warn("ASR transcribe failed: {}", e.getMessage());
            throw new BusinessException(ErrorCode.AI_PROVIDER_ERROR, "语音识别失败，请稍后再试");
        }
    }

    private void validateUpload(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "请上传音频文件");
        }
        if (file.getSize() > maxBytes) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "音频文件过大");
        }
        // MediaRecorder 常见 Content-Type: audio/webm;codecs=opus —— 必须去掉 ; 后参数再比对
        String contentType = normalizeContentType(file.getContentType());
        if (!contentType.isBlank() && !ALLOWED_CONTENT_TYPES.contains(contentType)) {
            log.warn("Rejected ASR upload contentType={} filename={}",
                    file.getContentType(), file.getOriginalFilename());
            throw new BusinessException(ErrorCode.BAD_REQUEST, "不支持的音频格式");
        }
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase(Locale.ROOT);
        boolean extOk = ALLOWED_EXTENSIONS.stream().anyMatch(name::endsWith);
        if (!extOk && !(contentType.startsWith("audio/") || "application/octet-stream".equals(contentType))) {
            log.warn("Rejected ASR upload extension/contentType filename={} contentType={}",
                    file.getOriginalFilename(), file.getContentType());
            throw new BusinessException(ErrorCode.BAD_REQUEST, "不支持的音频格式");
        }
    }

    /** Strip MIME parameters such as ";codecs=opus". Visible for unit tests. */
    static String normalizeContentType(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String lower = raw.toLowerCase(Locale.ROOT).trim();
        int semi = lower.indexOf(';');
        return (semi >= 0 ? lower.substring(0, semi) : lower).trim();
    }
}
