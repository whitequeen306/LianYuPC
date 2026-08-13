package com.lianyu.service.tools.bridge;

import com.lianyu.service.ai.ApiKeyVaultService;
import com.lianyu.service.dto.VaultEntryResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 本地引擎（AgentEngine）模型凭据解析：跟随用户文本渠道，一处配置处处共用。
 *
 * <p>取用户最近更新的启用文本 vault（与主动消息的 {@code resolvePreferredUserVault}
 * 同一语义），解密后下发给桌面端主进程，由其注入引擎子进程环境变量。
 * 仅返回给通过鉴权的本人；明文 Key 不落日志、不进任何 DTO 缓存。
 */
@Service
@RequiredArgsConstructor
public class EngineCredentialsService {

    private final ApiKeyVaultService vaultService;

    /** 下发给桌面端的引擎凭据；available=false 时其余字段为空串。 */
    public record EngineCredentials(boolean available, String baseUrl, String model, String apiKey) {

        static EngineCredentials unavailable() {
            return new EngineCredentials(false, "", "", "");
        }
    }

    public EngineCredentials resolve(Long userId) {
        VaultEntryResponse vault = vaultService.resolvePreferredUserVault(userId);
        if (vault == null || vault.getId() == null) {
            return EngineCredentials.unavailable();
        }
        String apiKey = vaultService.decryptKeyForChat(vault.getId());
        String model = vault.getModelDefault() == null ? "" : vault.getModelDefault().trim();
        return new EngineCredentials(true, toOpenAiSdkBaseUrl(vault.getBaseUrl()), model, apiKey);
    }

    /**
     * 归一化为 openai python SDK 需要的形态（请求发往 {@code {base_url}/chat/completions}，
     * 故须以 /v1 结尾）。后端 Spring AI 是「去 /v1 再自动补」，与存量两种格式都兼容。
     */
    static String toOpenAiSdkBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return "";
        }
        String trimmed = baseUrl.trim().replaceAll("/+$", "");
        return trimmed.endsWith("/v1") ? trimmed : trimmed + "/v1";
    }
}
