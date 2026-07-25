package com.lianyu.service.ai;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lianyu.common.base.ErrorCode;
import com.lianyu.common.constant.AiConstants;
import com.lianyu.common.exception.BusinessException;
import com.lianyu.common.util.OutboundUrlValidator;
import com.lianyu.dao.entity.ApiKeyVault;
import com.lianyu.dao.mapper.ApiKeyVaultMapper;
import com.lianyu.security.util.JasyptUtil;
import com.lianyu.service.dto.CreateVaultRequest;
import com.lianyu.service.dto.UpdateVaultRequest;
import com.lianyu.service.dto.VaultEntryResponse;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ApiKeyVaultService {

    public static final String USER_SCOPE = "USER";
    public static final String DEFAULT_SCOPE = "DEFAULT";
    /** 与 api_key_vault.provider VARCHAR(32) 一致 */
    public static final int MAX_PROVIDER_LENGTH = 32;
    private static final String DEFAULT_POOL_CURSOR_KEY = "vault:default:pool:cursor";
    /** 插入占位，插入后立即改为 Provider{id} */
    private static final String PROVIDER_INSERT_PLACEHOLDER = "p";

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
        if (alias != null && alias.length() > MAX_PROVIDER_LENGTH) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "配置别名不能超过 " + MAX_PROVIDER_LENGTH + " 个字符（请填简短名称，勿把 API Key 填在这里）");
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
        vault.setProvider(autoAlias ? PROVIDER_INSERT_PLACEHOLDER : alias);
        String apiKey = request.getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            apiKey = isOllamaEndpoint(baseUrl) ? "local" : "";
        }
        vault.setApiKeyEncrypted(jasyptUtil.encrypt(apiKey.trim()));
        vault.setKeyVersion(jasyptUtil.getCurrentVersion());
        vault.setBaseUrl(baseUrl);
        vault.setModelDefault(request.getModelDefault().trim());
        String visionDefault = trimToNull(request.getVisionModelDefault());
        if (visionDefault != null) {
            validateModelDefault(visionDefault);
        }
        vault.setVisionModelDefault(visionDefault);
        vault.setEnabled(1);
        vault.setRemark(trimToNull(request.getRemark()));
        vaultMapper.insert(vault);

        if (autoAlias) {
            String generated = "Provider" + vault.getId();
            if (generated.length() > MAX_PROVIDER_LENGTH) {
                throw new BusinessException(ErrorCode.INTERNAL_ERROR, "自动生成的配置别名过长");
            }
            vault.setProvider(generated);
            vaultMapper.updateById(vault);
        }

        log.info("API key vault created: userId={}, provider={}", userId, vault.getProvider());
        // 创建路径用刚写入的明文回填响应，避免「加密→立刻解密→格式启发式」误伤非 sk- Key（如 Gemini AIza…）
        return toResponse(vault, apiKey.trim(), true);
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
        if (request.getVisionModelDefault() != null) {
            String visionDefault = trimToNull(request.getVisionModelDefault());
            if (visionDefault != null) {
                validateModelDefault(visionDefault);
            }
            vault.setVisionModelDefault(visionDefault);
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
     * 2) provider 为空或 platform：仅走平台默认池（DEFAULT + provider=platform，轮询），
     *    不再回退到用户最近更新的私有 Vault，避免 UI 选「平台默认」仍走自定义 Key。
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
            if (plain == null || plain.isBlank()) {
                throw new BusinessException(ErrorCode.AI_PROVIDER_ERROR, "API Key 为空，请重新填写");
            }
            return plain.trim();
        } catch (IllegalStateException e) {
            // 仅在密码学解密失败时提示 master key；Key「好不好用」由拉取模型 / 实际对话验证
            throw new BusinessException(ErrorCode.AI_PROVIDER_ERROR,
                    "API Key 解密失败：LIANYU_MASTER_KEY 与入库时不一致。请用 backend/scripts/seed-default-vault-pool.ps1 重新加密写入 DEFAULT 池");
        }
    }

    private VaultEntryResponse toResponse(ApiKeyVault vault) {
        return toResponse(vault, decryptApiKeyOrThrow(vault.getApiKeyEncrypted()), false);
    }

    /**
     * 转换为响应 DTO。
     * @param vault    数据库实体
     * @param plainKey 已解密或创建时已知的明文 Key
     * @param showFull 是否返回完整 API Key（仅 create 时为 true）
     */
    private VaultEntryResponse toResponse(ApiKeyVault vault, String plainKey, boolean showFull) {
        return VaultEntryResponse.builder()
                .id(vault.getId())
                .userId(vault.getUserId())
                .vaultScope(vault.getVaultScope())
                .provider(vault.getProvider())
                .apiKey(showFull ? plainKey : maskApiKey(plainKey))
                .baseUrl(vault.getBaseUrl())
                .modelDefault(vault.getModelDefault())
                .visionModelDefault(vault.getVisionModelDefault())
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
                .apiKey(maskApiKey(decrypted))
                .baseUrl(vault.getBaseUrl())
                .modelDefault(vault.getModelDefault())
                .visionModelDefault(vault.getVisionModelDefault())
                .enabled(vault.getEnabled())
                .remark(vault.getRemark())
                .keyVersion(vault.getKeyVersion())
                .createdAt(vault.getCreatedAt())
                .updatedAt(vault.getUpdatedAt())
                .build();
    }

    /**
     * 仅供内部聊天连接用，返回明文 API Key。
     * 不得将此返回值存入 DTO 或序列化到日志。
     */
    public String decryptKeyForChat(Long vaultId) {
        ApiKeyVault vault = vaultMapper.selectById(vaultId);
        if (vault == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "Vault 配置不存在");
        }
        return decryptApiKeyOrThrow(vault.getApiKeyEncrypted());
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
