package com.lianyu.web.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lianyu.ai.SsrfPinningClientFactory;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
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

    /**
     * 主动发言链路挂的只读工具（能力声明归 Python，Java 只声明要哪些）。
     * 不含 write_diary / 桌面 desk_*：前者 Python 未实现（总纲 D9）；
     * 后者由 Python 的 capability_names() 按桌面在线状态决定，不该在这里写死。
     */
    private static final List<String> PROACTIVE_TOOLS = List.of(
            "get_current_time", "get_weather", "memory_search", "get_my_recent_life");

    private final AgentRuntimeProperties properties;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;
    /** 流式出站（对齐红线 §10：流式必须用 reactor-netty + 读间隙超时）。 */
    private final org.springframework.web.reactive.function.client.WebClient webClient;

    public AgentRuntimeClient(AgentRuntimeProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.restClient = SsrfPinningClientFactory.defaultRestClientBuilder()
                .baseUrl(properties.baseUrl())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
        this.webClient = SsrfPinningClientFactory.defaultWebClientBuilder()
                .baseUrl(properties.baseUrl())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                // 音频回传上限：Spring 默认 256KB，十秒语音就超了（见 postBytes 注释）
                .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
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
            Map<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("userId", userId);
            body.put("imageBase64", imageBase64 == null ? "" : imageBase64);
            body.put("windowTitle", windowTitle == null ? "" : windowTitle);
            body.put("persona", persona == null ? "" : persona);
            body.put("textBaseUrl", textBaseUrl == null ? "" : textBaseUrl);
            body.put("textApiKey", textApiKey == null ? "" : textApiKey);
            body.put("textModel", textModel == null ? "" : textModel);
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

    /**
     * 调用主动发言回合图（阶段 2：冷启动/跟进/城市变更/定时主动共用）。
     * Java 侧完成人设 system 组装与任务块选择；Python 侧做装配 + 模型调用。
     *
     * @return assistantRaw 或 null（任何失败静默降级，绝不影响主链路）
     */
    public String proactiveTurn(long userId,
                                String scene,
                                String persona,
                                String taskBlock,
                                List<Map<String, String>> history,
                                String textBaseUrl,
                                String textApiKey,
                                String textModel,
                                String provider,
                                String expectedLanguage,
                                Long characterId,
                                List<String> tools) {
        if (!properties.agentActive()) {
            return null;
        }
        try {
            Map<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("userId", userId);
            body.put("scene", scene == null ? "PROACTIVE" : scene);
            body.put("persona", persona == null ? "" : persona);
            body.put("taskBlock", taskBlock == null ? "" : taskBlock);
            body.put("history", history == null ? List.of() : history);
            body.put("textBaseUrl", textBaseUrl == null ? "" : textBaseUrl);
            body.put("textApiKey", textApiKey == null ? "" : textApiKey);
            body.put("textModel", textModel == null ? "" : textModel);
            // provider 用于 Python 侧按上游隔离熔断（对齐 UpstreamCircuitBreakerFactory）
            body.put("provider", provider == null ? "" : provider);
            // expectedLanguage 为空表示不走语言门（对齐 setExpectedLanguage 未设的语义）
            body.put("expectedLanguage", expectedLanguage == null ? "" : expectedLanguage);
            // characterId / tools：Python 侧工具上下文与可执行工具集
            body.put("characterId", characterId == null ? 0L : characterId);
            body.put("tools", tools == null ? List.of() : tools);
            String response = restClient.post()
                    .uri("/agent/proactive-turn")
                    .header("X-User-Id", String.valueOf(userId))
                    .body(body)
                    .retrieve()
                    .body(String.class);
            JsonNode root = objectMapper.readTree(response == null ? "{}" : response);
            if (root.hasNonNull("error")) {
                log.warn("Agent runtime proactiveTurn error: {}", root.path("error").asText());
                return null;
            }
            JsonNode raw = root.path("assistantRaw");
            return raw.isMissingNode() || raw.isNull() ? null : raw.asText();
        } catch (Exception e) {
            log.warn("Agent runtime proactiveTurn failed (degraded to null): {}", e.toString());
            return null;
        }
    }

    /**
     * 与 {@link #proactiveTurn} 同一请求，但带回 usage。
     * AGENT 模式下用它，避免 token 统计丢失；SHADOW 对比仍可用简化版。
     */
    public com.lianyu.service.agent.AgentTurnResult proactiveTurnResult(long userId,
                                String scene,
                                String persona,
                                String taskBlock,
                                List<Map<String, String>> history,
                                String textBaseUrl,
                                String textApiKey,
                                String textModel,
                                String provider,
                                String model,
                                String expectedLanguage,
                                Long characterId) {
        if (!properties.agentActive()) {
            return null;
        }
        List<String> tools = PROACTIVE_TOOLS;
        try {
            Map<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("userId", userId);
            body.put("scene", scene == null ? "PROACTIVE" : scene);
            body.put("persona", persona == null ? "" : persona);
            body.put("taskBlock", taskBlock == null ? "" : taskBlock);
            body.put("history", history == null ? List.of() : history);
            // 凭据必须随请求传入：Python 侧没有主密钥，不能自己去解密 vault
            body.put("textBaseUrl", textBaseUrl == null ? "" : textBaseUrl);
            body.put("textApiKey", textApiKey == null ? "" : textApiKey);
            body.put("textModel", textModel == null ? "" : textModel);
            body.put("provider", provider == null ? "" : provider);
            body.put("expectedLanguage", expectedLanguage == null ? "" : expectedLanguage);
            body.put("characterId", characterId == null ? 0L : characterId);
            body.put("tools", tools);
            String response = restClient.post()
                    .uri("/agent/proactive-turn")
                    .header("X-User-Id", String.valueOf(userId))
                    .body(body)
                    .retrieve()
                    .body(String.class);
            JsonNode root = objectMapper.readTree(response == null ? "{}" : response);
            if (root.hasNonNull("error")) {
                log.warn("Agent runtime proactiveTurn error: {}", root.path("error").asText());
                return null;
            }
            JsonNode raw = root.path("assistantRaw");
            String content = raw.isMissingNode() || raw.isNull() ? null : raw.asText();
            JsonNode total = root.path("totalTokens");
            Integer totalTokens = total.isMissingNode() || total.isNull() ? null : total.asInt();
            JsonNode rounds = root.path("toolRounds");
            JsonNode truncated = root.path("toolTruncated");
            if (rounds.asInt(0) > 0 || truncated.asBoolean(false)) {
                log.info("Agent proactiveTurn meta: userId={}, scene={}, toolRounds={}, truncated={}",
                        userId, scene, rounds.asInt(0), truncated.asBoolean(false));
            }
            return new com.lianyu.service.agent.AgentTurnResult(content, totalTokens);
        } catch (Exception e) {
            log.warn("Agent runtime proactiveTurnResult failed (degraded to null): {}", e.toString());
            return null;
        }
    }

    /**
     * 单聊流式：把 Python 的 SSE 行序列交给 {@code sink} 逐行处理。
     *
     * <p>返回 {@link reactor.core.publisher.Flux}，由调用方订阅并把行喂给
     * {@code AgentStreamBridge.Relay}；`[DONE]` 与收尾由 Java 侧决定（切分在 Java）。
     *
     * @return 行 Flux；未激活 AGENT 模式时返回空 Flux
     */
    public reactor.core.publisher.Flux<String> streamChat(long userId,
                                String system,
                                List<Map<String, String>> messages,
                                String textBaseUrl,
                                String textApiKey,
                                String textModel,
                                String provider,
                                String expectedLanguage,
                                Long characterId,
                                List<String> tools,
                                double temperature,
                                int maxTokens) {
        if (!properties.agentActive()) {
            return reactor.core.publisher.Flux.empty();
        }
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("userId", userId);
        body.put("system", system == null ? "" : system);
        body.put("messages", messages == null ? List.of() : messages);
        body.put("textBaseUrl", textBaseUrl == null ? "" : textBaseUrl);
        body.put("textApiKey", textApiKey == null ? "" : textApiKey);
        body.put("textModel", textModel == null ? "" : textModel);
        body.put("provider", provider == null ? "" : provider);
        body.put("expectedLanguage", expectedLanguage == null ? "" : expectedLanguage);
        body.put("characterId", characterId == null ? 0L : characterId);
        body.put("tools", tools == null ? List.of() : tools);
        body.put("temperature", temperature);
        body.put("maxTokens", maxTokens);

        return webClient.post()
                .uri("/agent/chat-stream")
                .header("X-User-Id", String.valueOf(userId))
                .bodyValue(body)
                .retrieve()
                .bodyToFlux(String.class);
    }

    /**
     * 通用流式 POST：把 Python 的 SSE 行序列交给调用方（帧解析与收尾在 Java 侧）。
     *
     * <p>与 {@link #streamChat} 同一出站策略（reactor-netty + 读间隙超时，红线 §10），
     * 区别只是 uri 与 body 由调用方给 —— 聊天回合端点的入参形状不同。
     */
    public reactor.core.publisher.Flux<String> postStream(String uri, long userId, Map<String, Object> body) {
        if (!properties.agentActive()) {
            return reactor.core.publisher.Flux.empty();
        }
        return webClient.post()
                .uri(uri)
                .header("X-User-Id", String.valueOf(userId))
                .bodyValue(body)
                .retrieve()
                .bodyToFlux(String.class);
    }

    /** 通用 POST：返回响应体字符串，失败返回 null（不抛）。 */
    public String postJson(String uri, long userId, Map<String, Object> body) {        try {
            return restClient.post()
                    .uri(uri)
                    .header("X-User-Id", String.valueOf(userId))
                    .body(body)
                    .retrieve()
                    .body(String.class);
        } catch (Exception e) {
            log.warn("Agent runtime POST {} failed: {}", uri, e.toString());
            return null;
        }
    }

    /**
     * 取**二进制**响应（TTS 音频）。2xx 返回内容；204 / 非 2xx / 异常一律返回 null。
     *
     * <p>为什么不能复用 {@link #postJson}：Spring 的 {@code StringDecoder} /
     * {@code ByteArrayDecoder} 默认 {@code maxInMemorySize = 256KB}，而十秒语音就有
     * 300KB+ —— 走 RestClient 会在**正常业务**上抛 {@code DataBufferLimitException}。
     * 这里用 WebClient（已在构造时把 maxInMemorySize 抬到 16MB）并阻塞等待，
     * 与原来 Apache HttpClient 的阻塞语义一致。
     *
     * <p>硬封顶 120s：Python 侧自身有 8s 连接 / 30s 读超时 + 最多两次尝试，
     * 这里只是兜底，避免上游卡死时把调用线程永久占住。
     */
    public BinaryResponse postBytes(String uri, long userId, Map<String, Object> body) {
        try {
            return webClient.post()
                    .uri(uri)
                    .header("X-User-Id", String.valueOf(userId))
                    .bodyValue(body)
                    .exchangeToMono(response -> {
                        int status = response.statusCode().value();
                        if (status == 204) {
                            return response.releaseBody().then(reactor.core.publisher.Mono.empty());
                        }
                        if (status < 200 || status >= 300) {
                            log.warn("Agent runtime POST {} returned {}", uri, status);
                            return response.releaseBody().then(reactor.core.publisher.Mono.empty());
                        }
                        String contentType = response.headers().contentType()
                                .map(MediaType::toString)
                                .orElse(null);
                        return response.bodyToMono(byte[].class)
                                .map(bytes -> new BinaryResponse(contentType, bytes));
                    })
                    .block(Duration.ofSeconds(120));
        } catch (Exception e) {
            log.warn("Agent runtime POST {} failed: {}", uri, e.toString());
            return null;
        }
    }

    /** 二进制响应（contentType 可能为 null）。 */
    public record BinaryResponse(String contentType, byte[] bytes) {
    }

    /**
     * 流式取**二进制块**（语音合成的 PCM 边收边播）。
     *
     * <p>与 {@link #postBytes} 的区别：不等完整响应，而是每收到一个 DataBuffer 就把字节
     * 交给下游 —— 语音通话的首包延迟因此从「整句合成完」降到「第一块就绪」
     * （实测 841ms vs 1440ms）。
     *
     * <p>与 {@link #postStream} 的区别：那条是 String/SSE（聊天文本）。音频**绝不能**
     * 走字符解码，否则字节会被字符集改写。这里保持 raw DataBuffer。
     *
     * <p>非 2xx 返回空 Flux（由调用方按"没有音频"降级），不抛异常；网络中断同理，
     * 已下发的块仍然有效。
     */
    public reactor.core.publisher.Flux<byte[]> postStreamBytes(String uri, long userId,
                                                               Map<String, Object> body) {
        if (!properties.agentActive()) {
            return reactor.core.publisher.Flux.empty();
        }
        return webClient.post()
                .uri(uri)
                .header("X-User-Id", String.valueOf(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchangeToFlux(response -> {
                    if (!response.statusCode().is2xxSuccessful()) {
                        log.warn("Agent runtime POST {} returned {}", uri, response.statusCode().value());
                        return response.releaseBody()
                                .thenMany(reactor.core.publisher.Flux.empty());
                    }
                    return response.bodyToFlux(DataBuffer.class)
                            .map(buffer -> {
                                byte[] chunk = new byte[buffer.readableByteCount()];
                                buffer.read(chunk);
                                DataBufferUtils.release(buffer);
                                return chunk;
                            })
                            .filter(chunk -> chunk.length > 0);
                });
    }

    /**
     * 轻量健康探测（GET /agent/health）。
     *
     * <p>用于 AGENT 模式下的**前置判断**：agent 宕机时立刻回退 Java，
     * 而不是等到流式请求发起后才失败（那时已经无法回退了）。
     */
    public boolean isHealthy() {
        try {
            String body = restClient.get()
                    .uri("/agent/health")
                    .retrieve()
                    .body(String.class);
            return body != null && body.contains("UP");
        } catch (Exception e) {
            log.debug("Agent health probe failed: {}", e.toString());
            return false;
        }
    }

    /** 解析 JSON；失败返回空对象，调用方用 path() 取值。 */
    public com.fasterxml.jackson.databind.JsonNode readTree(String raw) {
        try {
            return objectMapper.readTree(raw == null ? "{}" : raw);
        } catch (Exception e) {
            return objectMapper.createObjectNode();
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
