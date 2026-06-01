package com.lianyu.service;

import com.lianyu.dao.entity.ApiKeyVault;
import com.lianyu.dao.mapper.ApiKeyVaultMapper;
import com.lianyu.common.base.ErrorCode;
import com.lianyu.common.constant.AiConstants;
import com.lianyu.common.exception.BusinessException;
import com.lianyu.common.util.OutboundUrlValidator;
import com.lianyu.security.util.JasyptUtil;
import com.lianyu.service.dto.CreateVaultRequest;
import com.lianyu.service.dto.UpdateVaultRequest;
import com.lianyu.service.dto.VaultEntryResponse;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ApiKeyVaultService {

    public static final String USER_SCOPE = "USER";
    public static final String DEFAULT_SCOPE = "DEFAULT";
    private static final String DEFAULT_POOL_CURSOR_KEY = "vault:default:pool:cursor";

    private final ApiKeyVaultMapper vaultMapper;
    private final JasyptUtil jasyptUtil;
    private final StringRedisTemplate redisTemplate;

    @Transactional
    public VaultEntryResponse create(Long userId, CreateVaultRequest request) {
        String baseUrl = normalizeBaseUrl(request.getBaseUrl());
        validateVaultEndpoint(baseUrl, request.getApiKey());
        validateModelDefault(request.getModelDefault());

        String alias = trimToNull(request.getProvider());
        if (AiConstants.PLATFORM_PROVIDER.equalsIgnoreCase(alias)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "不能使用保留别名 platform");
        }
        boolean autoAlias = alias == null;

        if (!autoAlias) {
            boolean exists = vaultMapper.exists(new LambdaQueryWrapper<ApiKeyVault>()
                    .eq(ApiKeyVault::getUserId, userId)
                    .eq(ApiKeyVault::getProvider, alias));
            if (exists) {
                throw new BusinessException(ErrorCode.CONFLICT, "该别名已存在，请换一个名称");
            }
        }

        ApiKeyVault vault = new ApiKeyVault();
        vault.setUserId(userId);
        vault.setVaultScope(USER_SCOPE);
        vault.setProvider(autoAlias ? "Provider@" + java.util.UUID.randomUUID() : alias);
        String apiKey = request.getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            apiKey = isOllamaEndpoint(baseUrl) ? "local" : "";
        }
        vault.setApiKeyEncrypted(jasyptUtil.encrypt(apiKey.trim()));
        vault.setKeyVersion(jasyptUtil.getCurrentVersion());
        vault.setBaseUrl(baseUrl);
        vault.setModelDefault(request.getModelDefault().trim());
        vault.setEnabled(1);
        vault.setRemark(trimToNull(request.getRemark()));
        vaultMapper.insert(vault);

        if (autoAlias) {
            vault.setProvider("Provider" + vault.getId());
            vaultMapper.updateById(vault);
        }

        log.info("API key vault created: userId={}, provider={}", userId, vault.getProvider());
        return toResponse(vault, true);
    }

    public List<VaultEntryResponse> list(Long userId) {
        List<ApiKeyVault> vaults = vaultMapper.selectList(new LambdaQueryWrapper<ApiKeyVault>()
                .eq(ApiKeyVault::getUserId, userId)
                .eq(ApiKeyVault::getVaultScope, USER_SCOPE)
                .orderByDesc(ApiKeyVault::getUpdatedAt));
        return vaults.stream().map(this::toResponse).toList();
    }

    public VaultEntryResponse get(Long userId, String provider) {
        ApiKeyVault vault = vaultMapper.selectOne(new LambdaQueryWrapper<ApiKeyVault>()
                .eq(ApiKeyVault::getUserId, userId)
                .eq(ApiKeyVault::getVaultScope, USER_SCOPE)
                .eq(ApiKeyVault::getProvider, provider));
        if (vault == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "未找到该 provider 配置");
        }
        return toResponse(vault);
    }

    @Transactional
    public VaultEntryResponse update(Long userId, Long id, UpdateVaultRequest request) {
        ApiKeyVault vault = vaultMapper.selectById(id);
        if (vault == null || !userId.equals(vault.getUserId()) || !USER_SCOPE.equalsIgnoreCase(vault.getVaultScope())) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "未找到该 provider 配置");
        }

        if (request.getBaseUrl() != null) {
            if (request.getBaseUrl().isBlank()) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "Base URL 不能为空");
            }
            vault.setBaseUrl(normalizeBaseUrl(request.getBaseUrl()));
        }
        if (request.getApiKey() != null && !request.getApiKey().isBlank()) {
            vault.setApiKeyEncrypted(jasyptUtil.encrypt(request.getApiKey().trim()));
            vault.setKeyVersion(jasyptUtil.getCurrentVersion());
        }
        if (request.getModelDefault() != null) {
            validateModelDefault(request.getModelDefault());
            vault.setModelDefault(request.getModelDefault().trim());
        } else if (vault.getModelDefault() == null || vault.getModelDefault().isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "默认模型不能为空");
        }
        if (request.getEnabled() != null) {
            vault.setEnabled(request.getEnabled() != 0 ? 1 : 0);
        }
        if (request.getRemark() != null) {
            vault.setRemark(trimToNull(request.getRemark()));
        }
        vaultMapper.updateById(vault);

        log.info("API key vault updated: id={}, provider={}", id, vault.getProvider());
        return toResponse(vault);
    }

    @Transactional
    public void delete(Long userId, Long id) {
        ApiKeyVault vault = vaultMapper.selectById(id);
        if (vault == null || !userId.equals(vault.getUserId()) || !USER_SCOPE.equalsIgnoreCase(vault.getVaultScope())) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "未找到该 provider 配置");
        }
        vaultMapper.deleteById(id);
        log.info("API key vault deleted: id={}, provider={}", id, vault.getProvider());
    }

    /**
     * 对话时解析可用 Vault：
     * 1) provider 指定且非 platform：仅查用户私有配置；
     * 2) provider 为空或 platform：先用户私有（最近更新），再平台默认池（DEFAULT + provider=platform，轮询）。
     */
    public VaultEntryResponse resolveForChat(Long userId, String provider) {
        String target = trimToNull(provider);
        if (target != null && !AiConstants.PLATFORM_PROVIDER.equalsIgnoreCase(target)) {
            ApiKeyVault userVault = vaultMapper.selectOne(new LambdaQueryWrapper<ApiKeyVault>()
                    .eq(ApiKeyVault::getUserId, userId)
                    .eq(ApiKeyVault::getVaultScope, USER_SCOPE)
                    .eq(ApiKeyVault::getEnabled, 1)
                    .eq(ApiKeyVault::getProvider, target)
                    .last("LIMIT 1"));
            if (userVault == null) {
                throw new BusinessException(ErrorCode.NOT_FOUND, "未找到指定 AI 配置");
            }
            log.info("AI vault resolve: branch=USER_SPECIFIC, userId={}, provider={}, vaultId={}",
                    userId, target, userVault.getId());
            return toInternalResponse(userVault);
        }

        ApiKeyVault preferredUserVault = vaultMapper.selectOne(new LambdaQueryWrapper<ApiKeyVault>()
                .eq(ApiKeyVault::getUserId, userId)
                .eq(ApiKeyVault::getVaultScope, USER_SCOPE)
                .eq(ApiKeyVault::getEnabled, 1)
                .orderByDesc(ApiKeyVault::getUpdatedAt)
                .last("LIMIT 1"));
        if (preferredUserVault != null) {
            log.info("AI vault resolve: branch=USER_PREFERRED, userId={}, vaultId={}, provider={}, baseUrl={}",
                    userId, preferredUserVault.getId(), preferredUserVault.getProvider(),
                    preferredUserVault.getBaseUrl());
            return toInternalResponse(preferredUserVault);
        }

        List<ApiKeyVault> defaults = vaultMapper.selectList(new LambdaQueryWrapper<ApiKeyVault>()
                .eq(ApiKeyVault::getVaultScope, DEFAULT_SCOPE)
                .eq(ApiKeyVault::getEnabled, 1)
                .eq(ApiKeyVault::getProvider, AiConstants.PLATFORM_PROVIDER)
                .orderByAsc(ApiKeyVault::getId));
        if (defaults == null || defaults.isEmpty()) {
            log.info("AI vault resolve: branch=DEFAULT_POOL_EMPTY, userId={}, provider={}",
                    userId, provider);
            return null;
        }
        Long cursor = redisTemplate.opsForValue().increment(DEFAULT_POOL_CURSOR_KEY);
        int idx = Math.floorMod((cursor != null ? cursor.intValue() : 1) - 1, defaults.size());
        ApiKeyVault picked = defaults.get(idx);
        VaultEntryResponse response = toInternalResponse(picked);
        log.info("AI vault resolve: branch=DEFAULT_POOL, userId={}, poolSize={}, index={}, vaultId={}, "
                        + "baseUrl={}, modelDefault={}, key={}",
                userId, defaults.size(), idx, picked.getId(), picked.getBaseUrl(), picked.getModelDefault(),
                maskApiKey(response.getApiKey()));
        return response;
    }

    public String decryptKey(ApiKeyVault vault) {
        return decryptApiKeyOrThrow(vault.getApiKeyEncrypted());
    }

    private String decryptApiKeyOrThrow(String ciphertext) {
        try {
            String plain = jasyptUtil.decrypt(ciphertext);
            if (!JasyptUtil.looksLikeApiKey(plain)) {
                throw new IllegalStateException("decrypted value does not look like an API key");
            }
            return plain.trim();
        } catch (IllegalStateException e) {
            throw new BusinessException(ErrorCode.AI_PROVIDER_ERROR,
                    "API Key 解密失败：LIANYU_MASTER_KEY 与入库时不一致。请用 backend/scripts/seed-default-vault-pool.ps1 重新加密写入 DEFAULT 池");
        }
    }

    private VaultEntryResponse toResponse(ApiKeyVault vault) {
        return toResponse(vault, false);
    }

    /**
     * 转换为响应 DTO。
     * @param vault    数据库实体
     * @param showFull 是否返回完整 API Key（仅 create 时为 true）
     */
    private VaultEntryResponse toResponse(ApiKeyVault vault, boolean showFull) {
        String decrypted = decryptApiKeyOrThrow(vault.getApiKeyEncrypted());
        return VaultEntryResponse.builder()
                .id(vault.getId())
                .userId(vault.getUserId())
                .vaultScope(vault.getVaultScope())
                .provider(vault.getProvider())
                .apiKey(showFull ? decrypted : maskApiKey(decrypted))
                .baseUrl(vault.getBaseUrl())
                .modelDefault(vault.getModelDefault())
                .enabled(vault.getEnabled())
                .remark(vault.getRemark())
                .keyVersion(vault.getKeyVersion())
                .createdAt(vault.getCreatedAt())
                .updatedAt(vault.getUpdatedAt())
                .build();
    }

    private VaultEntryResponse toInternalResponse(ApiKeyVault vault) {
        String decrypted = decryptApiKeyOrThrow(vault.getApiKeyEncrypted());
        return VaultEntryResponse.builder()
                .id(vault.getId())
                .userId(vault.getUserId())
                .vaultScope(vault.getVaultScope())
                .provider(vault.getProvider())
                .apiKey(decrypted)
                .baseUrl(vault.getBaseUrl())
                .modelDefault(vault.getModelDefault())
                .enabled(vault.getEnabled())
                .remark(vault.getRemark())
                .keyVersion(vault.getKeyVersion())
                .createdAt(vault.getCreatedAt())
                .updatedAt(vault.getUpdatedAt())
                .build();
    }

    /**
     * 脱敏 API Key。
     * <ul>
     *   <li>{@code null / blank} → {@code ""}</li>
     *   <li>≤ 8 字符（如 ollama "local"）→ {@code "****"}</li>
     *   <li>其他 → 前 3 字符 + **** + 后 4 字符（如 {@code sk-****...abcd}）</li>
     * </ul>
     */
    static String maskApiKey(String key) {
        if (key == null || key.isBlank()) {
            return "";
        }
        String trimmed = key.trim();
        if (trimmed.length() <= 8) {
            return "****";
        }
        return trimmed.substring(0, 3) + "-****-" + trimmed.substring(trimmed.length() - 4);
    }

    private void validateModelDefault(String modelDefault) {
        if (modelDefault == null || modelDefault.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "默认模型不能为空");
        }
    }

    private void validateVaultEndpoint(String baseUrl, String apiKey) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Base URL 不能为空");
        }
        if (!isOllamaEndpoint(baseUrl) && (apiKey == null || apiKey.isBlank())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "API Key 不能为空");
        }
    }

    static boolean isOllamaEndpoint(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return false;
        }
        String lower = baseUrl.toLowerCase();
        return lower.contains(":11434") || lower.contains("ollama");
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String normalizeBaseUrl(String baseUrl) {
        boolean ollama = isOllamaEndpoint(baseUrl);
        return OutboundUrlValidator.validateAndNormalize(baseUrl, ollama);
    }
}
