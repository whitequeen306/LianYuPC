package com.lianyu.web.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lianyu.ai.SsrfPinningClientFactory;
import java.time.Duration;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * backend-agent HTTP 客户端（Java → Python 内网调用）。
 *
 * <p>红线遵守：出站走 {@link SsrfPinningClientFactory}（显式超时，§10）；
 * 仅访问容器内固定地址 agent:8001，不承载用户自填 URL，无 SSRF 面。
 */
@Slf4j
@Component
@EnableConfigurationProperties(AgentRuntimeProperties.class)
public class AgentRuntimeClient {

    private final AgentRuntimeProperties properties;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    public AgentRuntimeClient(AgentRuntimeProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.restClient = SsrfPinningClientFactory.defaultRestClientBuilder()
                .baseUrl(properties.baseUrl())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    /**
     * 调用桌面感知链路。Stage 2 文本模型凭据由调用方（Java 侧 vault 解析后）传入；
     * Python 侧任何失败一律返回 null（静默降级，绝不影响主链路）。
     *
     * @return greeting 或 null
     */
    public String desktopObserve(long userId,
                                 String imageBase64,
                                 String windowTitle,
                                 String persona,
                                 String textBaseUrl,
                                 String textApiKey,
                                 String textModel) {
        if (!properties.agentActive()) {
            return null;
        }
        try {
            Map<String, Object> body = Map.of(
                    "userId", userId,
                    "imageBase64", imageBase64 == null ? "" : imageBase64,
                    "windowTitle", windowTitle == null ? "" : windowTitle,
                    "persona", persona == null ? "" : persona,
                    "textBaseUrl", textBaseUrl == null ? "" : textBaseUrl,
                    "textApiKey", textApiKey == null ? "" : textApiKey,
                    "textModel", textModel == null ? "" : textModel);
            String response = restClient.post()
                    .uri("/agent/desktop-observe")
                    .header("X-User-Id", String.valueOf(userId))
                    .body(body)
                    .retrieve()
                    .body(String.class);
            JsonNode root = objectMapper.readTree(response == null ? "{}" : response);
            JsonNode greeting = root.path("greeting");
            return greeting.isMissingNode() || greeting.isNull() ? null : greeting.asText();
        } catch (Exception e) {
            log.warn("Agent runtime desktopObserve failed (degraded to null): {}", e.toString());
            return null;
        }
    }

    public AgentRuntimeProperties properties() {
        return properties;
    }

    /** 影子对比超时上限（超过即放弃等待并记 WARN）。 */
    public static Duration callTimeout() {
        return Duration.ofSeconds(90);
    }
}
