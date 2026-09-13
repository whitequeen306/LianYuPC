package com.lianyu.service.ai;

import com.lianyu.ai.SsrfPinningClientFactory;
import com.lianyu.common.util.OutboundUrlValidator;
import com.lianyu.service.dto.VaultEntryResponse;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 模型客户端工厂：按 vault 端点构建 Ollama / OpenAI-compatible {@link ChatModel}。
 * 出站一律走 {@link SsrfPinningClientFactory}（AGENTS.md §10 红线）：
 * 用户自填端点固定已校验 IP 防 DNS 重绑定；受信端点也显式带超时，防对端挂起 park 线程。
 */
@Component
public class ChatModelFactory {

    @Value("${spring.ai.openai.base-url:}")
    private String platformBaseUrl;

    public ChatModel buildChatModel(VaultEntryResponse vault, String model, String apiKey) {
        String baseUrl = vault.getBaseUrl();
        if (ApiKeyVaultService.isOllamaEndpoint(baseUrl)) {
            // 用户配置的 Ollama 端点：本地放行不固定；远程固定已校验 IP，防 DNS 重绑定 SSRF
            var endpoint = OutboundUrlValidator.validateAndResolve(baseUrl, true);
            OllamaApi ollamaApi = OllamaApi.builder()
                    .baseUrl(baseUrl)
                    .restClientBuilder(SsrfPinningClientFactory.restClientBuilder(endpoint))
                    .webClientBuilder(SsrfPinningClientFactory.webClientBuilder(endpoint))
                    .build();
            return OllamaChatModel.builder()
                    .ollamaApi(ollamaApi)
                    .defaultOptions(OllamaChatOptions.builder().model(model).build())
                    .build();
        }
        boolean userSupplied = baseUrl != null && !baseUrl.isBlank();
        String resolvedUrl = normalizeOpenAiBaseUrl(userSupplied ? baseUrl : platformBaseUrl);
        OpenAiApi.Builder openAiBuilder = OpenAiApi.builder()
                .baseUrl(resolvedUrl)
                .apiKey(apiKey);
        if (userSupplied && !OutboundUrlValidator.isTrustedPlatformEndpoint(baseUrl)) {
            // 仅对用户配置的 base_url 固定 IP（平台受信 DashScope / 默认 URL 不固定以支持 CDN 轮转）
            var endpoint = OutboundUrlValidator.validateAndResolve(baseUrl, false);
            openAiBuilder
                    .restClientBuilder(SsrfPinningClientFactory.restClientBuilder(endpoint))
                    .webClientBuilder(SsrfPinningClientFactory.webClientBuilder(endpoint));
        } else {
            // 受信端点也必须显式带超时：Spring AI 默认 RestClient/WebClient 走 reactor-netty 零超时，
            // 对端挂起时线程会永久 park 在 Mono.block()（timeLimiter 只放弃 Future，杀不死底层线程）。
            openAiBuilder
                    .restClientBuilder(SsrfPinningClientFactory.defaultRestClientBuilder())
                    .webClientBuilder(SsrfPinningClientFactory.defaultWebClientBuilder());
        }
        // 受信 DashScope/DeepSeek / 平台默认：不做 DNS 预解析与 IP 固定。
        // 容器 DNS 抖动时预解析会误报「主机名无法解析」，拖垮识图第二阶段文本调用并误开全局熔断。
        // 平台默认 URL 已在配置侧约束；受信域名由 isTrustedPlatformEndpoint 白名单覆盖。
        OpenAiApi openAiApi = openAiBuilder.build();
        return OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(OpenAiChatOptions.builder().model(model).build())
                .build();
    }

    /** Spring AI 会自动追加 /v1，Base URL 不应以 /v1 结尾，否则会变成 /v1/v1/... 导致 404 */
    public String normalizeOpenAiBaseUrl(String baseUrl) {
        String resolved = (baseUrl != null && !baseUrl.isBlank()) ? baseUrl : platformBaseUrl;
        if (resolved == null || resolved.isBlank()) {
            throw new IllegalStateException(
                    "OpenAI-compatible base URL not configured. Set vault base_url or OPENAI_BASE_URL.");
        }
        String trimmed = resolved.replaceAll("/$", "");
        if (trimmed.endsWith("/v1")) {
            return trimmed.substring(0, trimmed.length() - 3);
        }
        return trimmed;
    }
}
