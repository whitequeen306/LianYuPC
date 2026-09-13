package com.lianyu.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lianyu.ai.SsrfPinningClientFactory;
import com.lianyu.common.base.ErrorCode;
import com.lianyu.common.constant.AiConstants;
import com.lianyu.common.exception.BusinessException;
import com.lianyu.common.util.OutboundUrlValidator;
import com.lianyu.service.dto.ModelEntryDto;
import com.lianyu.service.dto.VaultEntryResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * 模型目录：拉取 provider 可用模型列表（OpenAI-compatible /v1/models、Ollama /api/tags）
 * + Redis 结果缓存（含防击穿锁与空结果短 TTL）。
 * 从 AiChatService 拆出：模型目录是低频配置查询，与高频聊天链路无共享状态。
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class ModelCatalogService {

    private static final String CACHE_KEY_PREFIX = "provider_models:";
    private static final String CACHE_LOCK_SUFFIX = ":lock";
    private static final Duration CACHE_TTL = Duration.ofHours(1);
    private static final Duration EMPTY_CACHE_BASE_TTL = Duration.ofMinutes(2);
    private static final Duration CACHE_LOCK_TTL = Duration.ofSeconds(10);

    /** 原子比较删除 Redis 缓存锁的 Lua 脚本（issue #19：避免 GET-then-DELETE 误删他人锁） */
    private static final DefaultRedisScript<Long> RELEASE_LOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    private final ApiKeyVaultService vaultService;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final ChatModelFactory chatModelFactory;

    @Value("${spring.ai.openai.base-url:}")
    private String platformBaseUrl;

    public List<ModelEntryDto> previewModels(Long userId, String baseUrl, String apiKey) {
        VaultEntryResponse transientVault = VaultEntryResponse.builder()
                .provider("__preview__")
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .build();
        try {
            return ApiKeyVaultService.isOllamaEndpoint(baseUrl)
                    ? fetchOllamaModels(transientVault)
                    : fetchOpenAiCompatibleModels(transientVault, apiKey != null ? apiKey : "");
        } catch (Exception e) {
            if (e instanceof BusinessException be) throw be;
            log.warn("previewModels failed: baseUrl={}, error={}", baseUrl, e.getMessage(), e);
            String em = e.getMessage();
            String hint;
            if (em != null && em.contains("401")) {
                hint = "API Key 无效或已过期，请检查密钥是否正确";
            } else if (em != null) {
                hint = em;
            } else {
                hint = "请检查接口地址和密钥后重试";
            }
            throw new BusinessException(ErrorCode.AI_PROVIDER_ERROR, "无法加载模型列表：" + hint);
        }
    }

    public List<ModelEntryDto> fetchModels(Long userId, String provider) {
        String cacheKey = CACHE_KEY_PREFIX + provider + ":" + userId;
        String cached = redisTemplate.opsForValue().get(cacheKey);
        if (cached != null) {
            try {
                return parseCachedModels(cached);
            } catch (Exception e) {
                log.warn("Failed to parse cached model list, will refetch", e);
            }
        }

        String lockKey = cacheKey + CACHE_LOCK_SUFFIX;
        String lockValue = UUID.randomUUID().toString();
        boolean locked = tryAcquireCacheLock(lockKey, lockValue);
        if (!locked) {
            // Briefly wait for the current rebuilding request to populate cache.
            sleepQuietly(80);
            String retriedCache = redisTemplate.opsForValue().get(cacheKey);
            if (retriedCache != null) {
                try {
                    return parseCachedModels(retriedCache);
                } catch (Exception e) {
                    log.warn("Failed to parse cached model list after wait, will refetch", e);
                }
            }
        }

        try {
            VaultEntryResponse vault = resolveVault(userId, provider);
            List<ModelEntryDto> models;
            models = ApiKeyVaultService.isOllamaEndpoint(vault.getBaseUrl())
                    ? fetchOllamaModels(vault)
                    : fetchOpenAiCompatibleModels(vault, resolveApiKeyForProvider(vault));
            cacheModels(cacheKey, models);

            return models;
        } catch (Exception e) {
            if (e instanceof BusinessException be) throw be;
            throw new BusinessException(ErrorCode.AI_PROVIDER_ERROR, "无法加载模型列表，请检查 AI 配置后重试");
        } finally {
            releaseCacheLock(lockKey, lockValue);
        }
    }

    private VaultEntryResponse resolveVault(Long userId, String provider) {
        if (provider == null || provider.isBlank()
                || AiConstants.PLATFORM_PROVIDER.equalsIgnoreCase(provider)) {
            throw new BusinessException(ErrorCode.AI_PROVIDER_ERROR,
                    "未配置文本模型，请在设置中添加");
        }
        VaultEntryResponse userVault = vaultService.resolveForChat(userId, provider);
        if (userVault == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "找不到该 AI 配置，请先在设置中添加");
        }
        log.info("Model catalog vault: userId={}, vaultId={}, provider={}, baseUrl={}, key={}",
                userId, userVault.getId(), userVault.getProvider(),
                userVault.getBaseUrl(), ApiKeyVaultService.maskApiKey(userVault.getApiKey()));
        return userVault;
    }

    private String resolveApiKeyForProvider(VaultEntryResponse vault) {
        if (vault.getId() != null) {
            return vaultService.decryptKeyForChat(vault.getId());
        }
        return vault.getApiKey();
    }

    private List<ModelEntryDto> parseCachedModels(String cached) throws Exception {
        JsonNode arr = objectMapper.readTree(cached);
        List<ModelEntryDto> models = new ArrayList<>();
        for (JsonNode node : arr) {
            models.add(ModelEntryDto.builder()
                    .id(node.get("id").asText())
                    .name(node.has("name") ? node.get("name").asText() : node.get("id").asText())
                    .build());
        }
        return models;
    }

    private List<ModelEntryDto> fetchOpenAiCompatibleModels(VaultEntryResponse vault, String apiKey) {
        String vaultBaseUrl = vault.getBaseUrl();
        boolean userSupplied = vaultBaseUrl != null && !vaultBaseUrl.isBlank();
        String base = chatModelFactory.normalizeOpenAiBaseUrl(userSupplied ? vaultBaseUrl : platformBaseUrl);
        String url = base + "/v1/models";

        // 用户自填非受信 base_url 固定已校验 IP 防 DNS 重绑定 SSRF；受信端点用带超时的默认客户端（防对端挂起无限 park 线程）
        RestClient client = userSupplied && !OutboundUrlValidator.isTrustedPlatformEndpoint(vaultBaseUrl)
                ? SsrfPinningClientFactory.restClientBuilder(
                        OutboundUrlValidator.validateAndResolve(vaultBaseUrl, false)).build()
                : SsrfPinningClientFactory.defaultRestClientBuilder().build();
        String body = client.get()
                .uri(url)
                .header("Authorization", "Bearer " + apiKey)
                .retrieve()
                .body(String.class);

        try {
            JsonNode root = objectMapper.readTree(body);
            List<ModelEntryDto> models = new ArrayList<>();
            JsonNode data = root.get("data");
            if (data != null && data.isArray()) {
                for (JsonNode item : data) {
                    String id = item.get("id").asText();
                    models.add(ModelEntryDto.builder().id(id).name(id).build());
                }
            }
            return models;
        } catch (Exception e) {
            log.warn("Failed to parse models response for provider={}", vault.getProvider(), e);
            return List.of();
        }
    }

    private List<ModelEntryDto> fetchOllamaModels(VaultEntryResponse vault) {
        String vaultBaseUrl = vault.getBaseUrl();
        boolean useLocal = vaultBaseUrl == null || vaultBaseUrl.isBlank();
        String baseUrl = useLocal ? "http://localhost:11434" : vaultBaseUrl;

        OllamaApi.Builder builder = OllamaApi.builder().baseUrl(baseUrl);
        if (!useLocal) {
            // 用户配置的远程 ollama：固定已校验 IP，防 DNS 重绑定 SSRF
            var endpoint = OutboundUrlValidator.validateAndResolve(vaultBaseUrl, true);
            builder.restClientBuilder(SsrfPinningClientFactory.restClientBuilder(endpoint))
                    .webClientBuilder(SsrfPinningClientFactory.webClientBuilder(endpoint));
        }
        OllamaApi ollamaApi = builder.build();
        var response = ollamaApi.listModels();
        return response.models().stream()
                .map(m -> ModelEntryDto.builder().id(m.model()).name(m.model()).build())
                .toList();
    }

    private void cacheModels(String cacheKey, List<ModelEntryDto> models) {
        try {
            Duration ttl = resolveCacheTtl(models);
            redisTemplate.opsForValue().set(cacheKey, objectMapper.writeValueAsString(models), ttl);
        } catch (Exception e) {
            log.warn("Failed to cache model list", e);
        }
    }

    private Duration resolveCacheTtl(List<ModelEntryDto> models) {
        if (models == null || models.isEmpty()) {
            // Empty result is cached shortly to reduce penetration/frequent misses.
            return EMPTY_CACHE_BASE_TTL.plusSeconds(ThreadLocalRandom.current().nextInt(10, 61));
        }
        // Add jitter to smooth expiration and reduce cache avalanche.
        return CACHE_TTL.plusMinutes(ThreadLocalRandom.current().nextInt(0, 11));
    }

    private boolean tryAcquireCacheLock(String lockKey, String lockValue) {
        Boolean locked = redisTemplate.opsForValue().setIfAbsent(lockKey, lockValue, CACHE_LOCK_TTL);
        return Boolean.TRUE.equals(locked);
    }

    private void releaseCacheLock(String lockKey, String lockValue) {
        try {
            // 原子比较删除：GET-then-DELETE 之间存在锁 TTL 到期被他人获取后误删他人锁的窗口（issue #19）
            redisTemplate.execute(RELEASE_LOCK_SCRIPT, List.of(lockKey), lockValue);
        } catch (Exception e) {
            log.debug("release cache lock failed: {}", e.getMessage());
        }
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
