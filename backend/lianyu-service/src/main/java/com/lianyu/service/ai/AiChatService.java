package com.lianyu.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lianyu.common.base.ErrorCode;
import com.lianyu.common.constant.AiConstants;
import com.lianyu.common.exception.BusinessException;
import com.lianyu.common.i18n.OutputLanguage;
import com.lianyu.common.util.CharacterSettingsUtils;
import com.lianyu.common.util.OutboundUrlValidator;
import com.lianyu.common.util.UserInputSanitizer;
import com.lianyu.service.dto.AiChatRequest;
import com.lianyu.service.dto.ChatResult;
import com.lianyu.service.dto.GenerateCharacterRequest;
import com.lianyu.service.dto.MessageDto;
import com.lianyu.service.dto.ModelEntryDto;
import com.lianyu.service.dto.VaultEntryResponse;
import com.lianyu.service.rules.PromptRuleEngine;
import com.lianyu.service.rules.PromptRuleSlot;
import com.lianyu.service.rules.PromptRuleContext;
import com.lianyu.service.support.OutputLanguageService;
import com.lianyu.service.storage.FileStorageService;
import com.lianyu.service.tools.ChatToolContext;
import com.lianyu.service.tools.ToolManager;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.content.Media;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaOptions;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Slf4j
@Service
public class AiChatService {

    private final ApiKeyVaultService vaultService;
    private final FileStorageService fileStorageService;
    private final ToolManager toolManager;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final Bulkhead bulkhead;
    private final TimeLimiter timeLimiter;
    private final CircuitBreaker circuitBreaker;
    private final ScheduledExecutorService scheduler;
    private final PromptRuleEngine promptRuleEngine;
    private final OutputLanguageService outputLanguageService;
    private final MultimodalOutputParser multimodalOutputParser;

    @Value("${spring.ai.openai.chat.options.model:}")
    private String defaultModel;

    @Value("${spring.ai.openai.base-url:}")
    private String platformBaseUrl;

    @Value("${lianyu.ai.multimodal.enabled:true}")
    private boolean multimodalEnabled;

    @Value("${lianyu.ai.multimodal.base-url:https://clove.dpdns.org/v1}")
    private String multimodalBaseUrl;

    @Value("${lianyu.ai.multimodal.api-key:}")
    private String multimodalApiKey;

    @Value("${lianyu.ai.multimodal.model:qwen3.7-plus}")
    private String multimodalModel;

    @Value("${lianyu.ai.multimodal.max-tokens:800}")
    private int multimodalMaxTokens;

    @Value("${lianyu.ai.multimodal.describe-max-tokens:420}")
    private int multimodalDescribeMaxTokens;

    private static final String CACHE_KEY_PREFIX = "provider_models:";
    private static final String CACHE_LOCK_SUFFIX = ":lock";
    private static final Duration CACHE_TTL = Duration.ofHours(1);
    private static final Duration EMPTY_CACHE_BASE_TTL = Duration.ofMinutes(2);
    private static final Duration CACHE_LOCK_TTL = Duration.ofSeconds(10);
    private static final String RESILIENCE_NAME = "ai-chat";
    private static final int LANGUAGE_GATE_MAX_RETRIES = 2;

    /**
     * 多模态图片回复规范：模型须先输出 <json> 结构化分析（子意图/置信度/图片理解），
     * 再以角色身份输出最终回复。禁止把图片理解直接念给用户。
     */
    private static final String MULTIMODAL_JSON_INSTRUCTION = """

            【图片回复规范——必须严格遵守】
            用户发了一张图片。你要先用 <json></json> 块输出结构化分析，再以你的角色身份输出最终回复。两步在同一次回复内完成，顺序为：先 <json> 块，随后另起一行写最终回复。

            <json>{"sub_intent":"用户发图的目的","confidence":"high|medium|low","image_description":"结合上下文推断的要点"}</json>
            （另起一行，以你的角色身份对用户说话）

            字段要求：
            - sub_intent：从以下选最接近的一个：分享日常 / 求识图认物 / 截图问问题 / 求情绪回应 / 炫耀 / 其他
            - confidence：你能否看清这张图。high=清楚；low=看不清/模糊/无法辨认；medium=部分清楚
            - image_description：结合对话上下文推断出的要点，不是逐字客观描述。例如用户说"看我今天吃的"就聚焦食物；不要机械罗列画面元素。

            回复要求：
            - <json> 块之后那段话才是给用户看的最终回复，必须以你的角色身份、性格、语气来说，结合上下文自然回应。
            - 绝不允许把 image_description 直接念给用户当回复。
            - 若 confidence 为 low，最终回复要明确告诉用户你看不清这张图，并请其重发或描述一下。
            - 最终回复里不要再出现 JSON 或"以下是分析"之类的话。
            """;

    public AiChatService(ApiKeyVaultService vaultService,
                         FileStorageService fileStorageService,
                         ToolManager toolManager,
                         StringRedisTemplate redisTemplate,
                         ObjectMapper objectMapper,
                         BulkheadRegistry bulkheadRegistry,
                         TimeLimiterRegistry timeLimiterRegistry,
                         CircuitBreakerRegistry circuitBreakerRegistry,
                         ScheduledExecutorService scheduler,
                         PromptRuleEngine promptRuleEngine,
                         OutputLanguageService outputLanguageService) {
        this.vaultService = vaultService;
        this.fileStorageService = fileStorageService;
        this.toolManager = toolManager;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.bulkhead = bulkheadRegistry.bulkhead(RESILIENCE_NAME);
        this.timeLimiter = timeLimiterRegistry.timeLimiter(RESILIENCE_NAME);
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker(RESILIENCE_NAME);
        this.scheduler = scheduler;
        this.promptRuleEngine = promptRuleEngine;
        this.outputLanguageService = outputLanguageService;
        this.multimodalOutputParser = new MultimodalOutputParser(objectMapper);
    }

    public SseEmitter chatStream(Long userId, AiChatRequest request) {
        return chatStream(userId, request, null);
    }

    public SseEmitter chatStream(Long userId, AiChatRequest request, StreamCallback callback) {
        if (!bulkhead.tryAcquirePermission()) {
            throw new BusinessException(ErrorCode.AI_RATE_LIMITED, "对话服务繁忙，请稍后再试");
        }

        try {
            VaultEntryResponse vault = resolveVault(userId, request.getProvider());
            String model = resolveModel(request, vault);
            logChatVaultUsage(userId, request.getProvider(), vault, model, "stream");
            ChatModel chatModel = buildChatModel(vault, model, vaultService.decryptKeyForChat(vault.getId()));

            SseEmitter emitter = new SseEmitter(300_000L);
            StringBuilder contentBuffer = new StringBuilder();

            CompletableFuture.runAsync(() -> {
                try {
                    runWithChatToolScope(userId, request, () -> {
                        List<Message> messages = toSpringMessages(request.getMessages());
                        Prompt prompt = buildPrompt(request, vault, messages);
                        chatModel.stream(prompt)
                                .doOnNext(response -> {
                                    try {
                                        String text = extractStreamDelta(response);
                                        if (text != null && !text.isEmpty()) {
                                            contentBuffer.append(text);
                                            sendSseChunk(emitter, text);
                                        }
                                    } catch (IOException e) {
                                        log.error("SSE send error", e);
                                    }
                                })
                                .doOnComplete(() -> {
                                    try {
                                        String finalContent = contentBuffer.toString();
                                        String corrected = enforceExpectedLanguage(
                                                userId,
                                                request,
                                                vault,
                                                model,
                                                chatModel,
                                                finalContent);
                                        if (corrected != null
                                                && !corrected.equals(finalContent)
                                                && !corrected.isBlank()) {
                                            sendSseReplace(emitter, corrected);
                                            finishSseSuccess(emitter, corrected, callback);
                                            return;
                                        }
                                        finishSseSuccess(emitter, finalContent, callback);
                                    } catch (Exception e) {
                                        log.error("SSE language correction failed", e);
                                        finishSseSuccess(emitter, contentBuffer.toString(), callback);
                                    }
                                })
                                .onErrorResume(e -> {
                                    log.error("AI stream error", e);
                                    finishSseError(emitter, resolveStreamErrorMessage(e), contentBuffer.toString(), callback);
                                    return reactor.core.publisher.Mono.empty();
                                })
                                .blockLast();
                    });
                } catch (Exception e) {
                    log.error("AI chat stream fatal error", e);
                    finishSseError(emitter, resolveStreamErrorMessage(e), contentBuffer.toString(), callback);
                } finally {
                    bulkhead.releasePermission();
                }
            }, scheduler);

            return emitter;
        } catch (RuntimeException e) {
            bulkhead.releasePermission();
            throw e;
        } catch (Exception e) {
            bulkhead.releasePermission();
            throw new BusinessException(ErrorCode.AI_PROVIDER_ERROR, "消息发送失败，请稍后再试");
        }
    }

    @FunctionalInterface
    public interface StreamCallback {
        void onComplete(String fullContent, Throwable error);

        /**
         * 在发送 [DONE] 之前调用，可向客户端推送规范化后的分片（pieces）等。
         */
        default void beforeStreamComplete(SseEmitter emitter, String fullContent) throws IOException {
            // no-op
        }
    }

    public ChatResult chatBlocking(Long userId, AiChatRequest request) {
        try {
            return timeLimiter.executeCompletionStage(scheduler, () ->
                    CompletableFuture.supplyAsync(() -> {
                        try {
                            return bulkhead.executeCallable(() ->
                                    circuitBreaker.executeCallable(() -> {
                                        VaultEntryResponse vault = resolveVault(userId, request.getProvider());
                                        String model = resolveModel(request, vault);
                                        ChatModel chatModel = buildChatModel(vault, model, vaultService.decryptKeyForChat(vault.getId()));

                                        return withChatToolScope(userId, request, () -> {
                                        List<Message> messages = toSpringMessages(request.getMessages());
                                        Prompt prompt = buildPrompt(request, vault, messages);
                                        ChatResponse response = chatModel.call(prompt);
                                        String content = extractStreamDelta(response);
                                        if (content == null) {
                                            throw new BusinessException(ErrorCode.AI_PROVIDER_ERROR,
                                                    "对方没有回复内容，请重试");
                                        }
                                        content = enforceExpectedLanguage(
                                                userId,
                                                request,
                                                vault,
                                                model,
                                                chatModel,
                                                content);

                                        ChatResult.ChatResultBuilder builder = ChatResult.builder().content(content);
                                        if (response.getMetadata() != null && response.getMetadata().getUsage() != null) {
                                            var usage = response.getMetadata().getUsage();
                                            builder.promptTokens(usage.getPromptTokens() != null ? usage.getPromptTokens().intValue() : null)
                                                   .completionTokens(usage.getCompletionTokens() != null ? usage.getCompletionTokens().intValue() : null)
                                                   .totalTokens(usage.getTotalTokens() != null ? usage.getTotalTokens().intValue() : null);
                                        }
                                        return builder.build();
                                        });
                                    })
                            );
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    })
            ).toCompletableFuture().join();
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof BusinessException be) {
                throw be;
            }
            log.error("AI chat error", cause);
            throw new BusinessException(ErrorCode.AI_PROVIDER_ERROR, "消息发送失败，请稍后再试");
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

    public Map<String, Object> generateCharacter(Long userId, GenerateCharacterRequest request) {
        VaultEntryResponse vault = resolveVaultForGeneration(userId, request.getProvider());
        try {
            return generateCharacterWithVault(vault, request.getDescription());
        } catch (Exception e) {
            String reason = simplifyGenerationError(e);
            log.warn("Character generation failed with provider={}, reason={}", vault.getProvider(), reason);
            throw new BusinessException(ErrorCode.AI_PROVIDER_ERROR, "角色设定生成失败，请稍后再试");
        }
    }

    private Map<String, Object> generateCharacterWithVault(VaultEntryResponse vault, String description) {
        String model = resolveGenerationModel(vault);
        ChatModel chatModel = buildChatModel(vault, model, vaultService.decryptKeyForChat(vault.getId()));

        String sysPrompt = """
                你是“虚拟恋人角色设定助手”。任务是根据用户提供的动漫/游戏/小说角色信息，生成可直接用于AI角色扮演的设定。
                不要联网搜索，不要声称查阅了网页，只基于常识与用户输入生成。

                你必须只输出 JSON 对象，不要输出 markdown，不要输出解释文字。
                JSON 字段固定为：
                {
                  "name": "角色名",
                  "age": "年龄或未知",
                  "gender": "性别或未知",
                  "speakingStyle": "说话风格（简短）",
                  "promptTemplate": "150~260字的中文角色设定，包含性格、语气、边界和互动方式，适合直接放入系统Prompt"
                }
                """;

        // 注入角色生成质量标准规则（来自 CharacterGenerationRuleHook）
        String genRules = promptRuleEngine.render(
                PromptRuleSlot.CHARACTER_GENERATION,
                new PromptRuleContext(null, null, null, null, null, null, null, null));
        if (!genRules.isBlank()) {
            sysPrompt += "\n\n" + genRules;
        }
        String safeDescription = UserInputSanitizer.sanitizeGenerationDescription(description);
        String userPrompt = "角色描述：" + safeDescription;

        List<Message> messages = List.of(new SystemMessage(sysPrompt), new UserMessage(userPrompt));
        Prompt prompt = buildGenerationPrompt(vault, model, messages);

        ChatResponse response;
        try {
            response = chatModel.call(prompt);
        } catch (Exception e) {
            // DeepSeek 侧出现 5xx 时，回退到官方通用模型再试一次，提升可用性。
            if (isDeepSeekEndpoint(vault.getBaseUrl()) && !"deepseek-chat".equals(model)) {
                String fallbackModel = "deepseek-chat";
                log.warn("Character generation retry with fallback model={}, provider={}",
                        fallbackModel, vault.getProvider());
                response = buildChatModel(vault, fallbackModel, vaultService.decryptKeyForChat(vault.getId()))
                        .call(buildGenerationPrompt(vault, fallbackModel, messages));
                model = fallbackModel;
            } else {
                throw e;
            }
        }
        String content = extractStreamDelta(response);
        if (content == null || content.isBlank()) {
            throw new BusinessException(ErrorCode.AI_PROVIDER_ERROR, "生成失败，请换个描述再试");
        }
        String cleaned = extractJsonObject(content.trim());
        JsonNode root;
        try {
            root = objectMapper.readTree(cleaned);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.AI_PROVIDER_ERROR, "生成结果格式异常，请重试");
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", CharacterSettingsUtils.fixUtf8Mojibake(valueOrDefault(root, "name", "未命名角色")));
        result.put("age", CharacterSettingsUtils.fixUtf8Mojibake(valueOrDefault(root, "age", "未知")));
        result.put("gender", CharacterSettingsUtils.fixUtf8Mojibake(valueOrDefault(root, "gender", "未知")));
        result.put("speakingStyle", CharacterSettingsUtils.fixUtf8Mojibake(valueOrDefault(root, "speakingStyle", "温柔")));
        result.put("promptTemplate", CharacterSettingsUtils.fixUtf8Mojibake(valueOrDefault(root, "promptTemplate", content.trim())));
        result.put("provider", vault.getProvider());
        result.put("model", model);
        return result;
    }

    /**
     * 根据角色名与人设推断其所在虚构城市（作品 canonical 设定优先）。
     */
    public String inferFictionalCity(Long userId, String characterName, String promptTemplate) {
        VaultEntryResponse vault = resolveVaultForGeneration(userId, null);
        try {
            return inferFictionalCityWithVault(vault, characterName, promptTemplate);
        } catch (Exception e) {
            String reason = simplifyGenerationError(e);
            log.warn("Fictional city inference failed: userId={}, name={}, reason={}",
                    userId, characterName, reason);
            throw new BusinessException(ErrorCode.AI_PROVIDER_ERROR, "由于角色背景原因虚构失败，建议您选择现实城市");
        }
    }

    private String inferFictionalCityWithVault(VaultEntryResponse vault, String characterName, String promptTemplate) {
        String model = resolveGenerationModel(vault);
        ChatModel chatModel = buildChatModel(vault, model, vaultService.decryptKeyForChat(vault.getId()));

        String safeName = UserInputSanitizer.sanitizeGenerationDescription(
                characterName != null ? characterName : "未知角色");
        String safePrompt = UserInputSanitizer.sanitizeGenerationDescription(
                promptTemplate != null ? promptTemplate : "");

        String sysPrompt = """
                你是动漫/游戏/小说地理设定助手。根据角色名称与人设，推断该角色在其原作中主要活动或居住的「虚构城市/地点」名称。
                不要联网搜索。只基于常识与给定文本推断；若原作无明确城市，可给出最合理的虚构地名或「未知」。
                只输出 JSON：{"fictionalCity":"城市或地点名"}。不要 markdown，不要解释。
                城市名用中文，尽量简短（如 天宫市、蒙德城、学园都市）。
                """;
        String userPrompt = "角色名：" + safeName + "\n人设摘要："
                + (safePrompt.isBlank() ? "（无）" : safePrompt.substring(0, Math.min(safePrompt.length(), 800)));

        List<Message> messages = List.of(new SystemMessage(sysPrompt), new UserMessage(userPrompt));
        Prompt prompt = buildGenerationPrompt(vault, model, messages);

        ChatResponse response;
        try {
            response = chatModel.call(prompt);
        } catch (Exception e) {
            if (isDeepSeekEndpoint(vault.getBaseUrl()) && !"deepseek-chat".equals(model)) {
                String fallbackModel = "deepseek-chat";
                response = buildChatModel(vault, fallbackModel, vaultService.decryptKeyForChat(vault.getId()))
                        .call(buildGenerationPrompt(vault, fallbackModel, messages));
            } else {
                throw e;
            }
        }

        String content = extractStreamDelta(response);
        if (content == null || content.isBlank()) {
            return "";
        }
        String cleaned = extractJsonObject(content.trim());
        JsonNode root;
        try {
            root = objectMapper.readTree(cleaned);
        } catch (Exception e) {
            return "";
        }
        String city = CharacterSettingsUtils.fixUtf8Mojibake(valueOrDefault(root, "fictionalCity", ""));
        if ("未知".equals(city) || city.isBlank()) {
            return "";
        }
        return city.trim();
    }

    private String resolveGenerationModel(VaultEntryResponse vault) {
        return resolveChatModel(null, vault);
    }

    private boolean isDeepSeekEndpoint(String baseUrl) {
        return baseUrl != null && baseUrl.toLowerCase().contains("deepseek.com");
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

    private String resolveApiKeyForProvider(VaultEntryResponse vault) {
        if (vault.getId() != null) {
            return vaultService.decryptKeyForChat(vault.getId());
        }
        return vault.getApiKey();
    }

    private List<ModelEntryDto> fetchOpenAiCompatibleModels(VaultEntryResponse vault, String apiKey) {
        String base = normalizeOpenAiBaseUrl(vault.getBaseUrl() != null ? vault.getBaseUrl() : platformBaseUrl);
        String url = base + "/v1/models";

        RestClient client = RestClient.create();
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
        String baseUrl = vault.getBaseUrl() != null ? vault.getBaseUrl() : "http://localhost:11434";

        OllamaApi ollamaApi = OllamaApi.builder().baseUrl(baseUrl).build();
        var response = ollamaApi.listModels();
        return response.models().stream()
                .map(m -> ModelEntryDto.builder().id(m.model()).name(m.model()).build())
                .toList();
    }

    private VaultEntryResponse resolveVault(Long userId, String provider) {
        if (isPlatformProvider(provider)) {
            VaultEntryResponse dbVault = vaultService.resolveForChat(userId, provider);
            if (dbVault != null) {
                log.info("AI chat vault: source=DB, userId={}, scope={}, vaultId={}, provider={}, baseUrl={}, "
                                + "modelDefault={}, key={}",
                        userId, dbVault.getVaultScope(), dbVault.getId(), dbVault.getProvider(),
                        dbVault.getBaseUrl(), dbVault.getModelDefault(),
                        ApiKeyVaultService.maskApiKey(dbVault.getApiKey()));
                return dbVault;
            }
            throw new BusinessException(ErrorCode.AI_PROVIDER_ERROR,
                    "平台对话服务未就绪，请稍后再试");
        }
        VaultEntryResponse userVault = vaultService.resolveForChat(userId, provider);
        if (userVault == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "找不到该 AI 配置，请先在设置中添加");
        }
        log.info("AI chat vault: source=DB_USER, userId={}, scope={}, vaultId={}, provider={}, baseUrl={}, "
                        + "modelDefault={}, key={}",
                userId, userVault.getVaultScope(), userVault.getId(), userVault.getProvider(),
                userVault.getBaseUrl(), userVault.getModelDefault(),
                ApiKeyVaultService.maskApiKey(userVault.getApiKey()));
        return userVault;
    }

    private void logChatVaultUsage(Long userId, String provider, VaultEntryResponse vault, String model, String mode) {
        String source = vault.getId() == null ? "ENV" : vault.getVaultScope();
        log.info("AI chat {}: userId={}, requestProvider={}, vaultSource={}, vaultId={}, model={}, baseUrl={}, key={}",
                mode, userId, provider, source, vault.getId(), model, vault.getBaseUrl(),
                ApiKeyVaultService.maskApiKey(vault.getApiKey()));
    }

    private VaultEntryResponse resolveVaultForGeneration(Long userId, String provider) {
        return resolveVault(userId, provider);
    }

    /**
     * 桌面感知：截图 VL 识图 → 角色语气生成主动问候。
     */
    public String observeDesktop(String imageBase64, String windowTitle, String persona) {
        if (!multimodalEnabled) {
            return null;
        }
        VaultEntryResponse visionVault = buildMultimodalVault();
        ChatModel chatModel = buildChatModel(visionVault, multimodalModel, visionVault.getApiKey());

        byte[] imageBytes;
        try {
            imageBytes = java.util.Base64.getDecoder().decode(imageBase64);
        } catch (IllegalArgumentException e) {
            log.warn("Desktop observe: invalid base64 image");
            return null;
        }

        // Stage 1: VL 识图
        Media media = Media.builder()
                .data(new ByteArrayResource(imageBytes))
                .mimeType(MimeTypeUtils.IMAGE_PNG)
                .build();
        Message vlMessage = UserMessage.builder()
                .text("请客观简洁描述这张屏幕截图的内容。"
                + "如果图中包含知名游戏/软件界面/视频画面，请明确指出。"
                + "如果图中包含动漫/游戏角色，用\"角色：XXX\"格式写出角色名。")
                .media(media)
                .build();
        List<Message> vlMessages = List.of(
                new SystemMessage("你是屏幕内容识别助手。只输出客观描述，不扮演角色，不闲聊。"),
                vlMessage);
        Prompt vlPromptObj = new Prompt(vlMessages, OpenAiChatOptions.builder()
                .model(multimodalModel).temperature(0.2).maxTokens(multimodalDescribeMaxTokens).build());
        ChatResponse vlResponse = chatModel.call(vlPromptObj);
        String description = extractStreamDelta(vlResponse);
        if (description == null || description.isBlank()) {
            log.warn("Desktop observe: VL returned empty description");
            return null;
        }
        log.info("Desktop observe: VL description ({} chars)", description.length());

        // Stage 2: 角色语气生成问候
        String personaText = (persona != null && !persona.isBlank()) ? persona : "你是一个可爱的桌面宠物。";
        String winTitle = (windowTitle != null && !windowTitle.isBlank()) ? windowTitle : "未知";
        String greetingPrompt = personaText + "\n\n"
                + "你正在看着用户的电脑屏幕。当前画面：" + description + "\n"
                + "用户正在使用的窗口：" + winTitle + "\n\n"
                + "请用你的角色语气，对用户正在做的事情说一句话（像打招呼或随口感叹）。\n"
                + "要求：自然、口语化、不超过40字。不要加上动作描述或括号。直接输出说的话。";

        List<Message> greetingMessages = List.of(new UserMessage(greetingPrompt));
        Prompt greetingPromptObj = new Prompt(greetingMessages, OpenAiChatOptions.builder()
                .model(multimodalModel).temperature(0.9).maxTokens(120).build());
        ChatResponse greetingResponse = chatModel.call(greetingPromptObj);
        String greeting = extractStreamDelta(greetingResponse);
        if (greeting == null || greeting.isBlank()) {
            log.warn("Desktop observe: greeting generation returned empty");
            return null;
        }
        log.info("Desktop observe: greeting generated ({} chars): {}", greeting.length(), greeting);
        return greeting.trim();
    }

    /** 多模态模型 Vault（自建中转 url/key，qwen3.7-plus），图片消息与桌宠屏幕观察共用。 */
    private VaultEntryResponse buildMultimodalVault() {
        String apiKey = multimodalApiKey;
        if (apiKey == null || apiKey.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "多模态识图服务未配置");
        }
        return VaultEntryResponse.builder()
                .provider(AiConstants.PLATFORM_PROVIDER)
                .apiKey(apiKey)
                .baseUrl(multimodalBaseUrl)
                .modelDefault(multimodalModel)
                .build();
    }

    /**
     * 图片消息专用多模态调用：携带完整角色上下文（system prompt 已由 ConversationService 用
     * CharacterPromptBuilder 组装好人设/记忆/关系/情绪），图片以 inline base64 发送，
     * 一次调用内先输出结构化 JSON 再输出角色回复（由 {@link MultimodalOutputParser} 解析）。
     */
    public ChatResult chatImageBlocking(Long userId, AiChatRequest request) {
        try {
            return bulkhead.executeCallable(() ->
                    circuitBreaker.executeCallable(() -> doImageChat(userId, request)));
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof BusinessException be) {
                throw be;
            }
            log.error("Multimodal chat error: userId={}", userId, cause);
            throw new BusinessException(ErrorCode.AI_PROVIDER_ERROR, "消息发送失败，请稍后再试");
        }
    }

    /**
     * 图片消息流式入口：多模态调用本身是阻塞的（需先完整解析 JSON 再决定回复），
     * 这里在调度线程内完成阻塞调用后，把回复以 SSE chunk 形式下发，复用 {@link StreamCallback}
     * 保证后处理（pieces 拆分、落库、关系/情绪更新）与纯文本链路一致。
     */
    public SseEmitter chatImageStream(Long userId, AiChatRequest request, StreamCallback callback) {
        if (!bulkhead.tryAcquirePermission()) {
            throw new BusinessException(ErrorCode.AI_RATE_LIMITED, "对话服务繁忙，请稍后再试");
        }
        SseEmitter emitter = new SseEmitter(300_000L);
        CompletableFuture.runAsync(() -> {
            try {
                ChatResult result = circuitBreaker.executeCallable(() -> doImageChat(userId, request));
                String reply = result.getContent();
                if (reply != null && !reply.isBlank()) {
                    sendSseChunk(emitter, reply);
                }
                finishSseSuccess(emitter, reply, callback);
            } catch (Exception e) {
                log.error("Multimodal stream error: userId={}", userId, e);
                finishSseError(emitter, resolveStreamErrorMessage(e), "", callback);
            } finally {
                bulkhead.releasePermission();
            }
        }, scheduler);
        return emitter;
    }

    private ChatResult doImageChat(Long userId, AiChatRequest request) {
        if (!multimodalEnabled) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "图片识别功能未启用");
        }
        VaultEntryResponse vault = buildMultimodalVault();
        ChatModel chatModel = buildChatModel(vault, multimodalModel, vault.getApiKey());
        List<Message> messages = buildMultimodalMessages(request.getMessages(), request.getImageUrl());
        Prompt prompt = new Prompt(messages, OpenAiChatOptions.builder()
                .model(multimodalModel)
                .temperature(0.8)
                .maxTokens(multimodalMaxTokens)
                .build());
        ChatResponse response = chatModel.call(prompt);
        String raw = extractStreamDelta(response);
        if (raw == null || raw.isBlank()) {
            throw new BusinessException(ErrorCode.AI_PROVIDER_ERROR, "图片识别失败，请换一张图片再试");
        }
        MultimodalOutputParser.Result parsed = multimodalOutputParser.parse(raw);
        String reply = (parsed.reply() == null || parsed.reply().isBlank()) ? raw : parsed.reply();
        log.info("Multimodal chat: userId={}, subIntent={}, confidence={}, low={}",
                userId, parsed.subIntent(), parsed.confidence(),
                MultimodalOutputParser.isLowConfidence(parsed.confidence()));
        ChatResult.ChatResultBuilder builder = ChatResult.builder().content(reply);
        if (response.getMetadata() != null && response.getMetadata().getUsage() != null) {
            var usage = response.getMetadata().getUsage();
            builder.totalTokens(usage.getTotalTokens() != null ? usage.getTotalTokens().intValue() : null);
        }
        return builder.build();
    }

    /**
     * 组装多模态 prompt：system(角色 prompt + 结构化JSON指令) + 历史(纯文本) + 末尾用户消息(图+文字)。
     * 图片从 MinIO 读出转 inline base64（中转服务拉不到相对路径），复用 {@link #buildVisionUserMessage}。
     */
    private List<Message> buildMultimodalMessages(List<MessageDto> dtos, String imageUrl) {
        if (dtos == null || dtos.isEmpty()) {
            return List.of();
        }
        List<Message> messages = new ArrayList<>();
        int start = 0;
        if ("system".equalsIgnoreCase(dtos.get(0).getRole())) {
            String sysContent = dtos.get(0).getContent();
            messages.add(new SystemMessage(
                    (sysContent != null ? sysContent : "") + "\n\n" + MULTIMODAL_JSON_INSTRUCTION));
            start = 1;
        }
        if (start < dtos.size() - 1) {
            messages.addAll(toSpringMessages(dtos.subList(start, dtos.size() - 1)));
        }
        if (start < dtos.size()) {
            MessageDto last = dtos.get(dtos.size() - 1);
            String text = (last.getContent() != null && !last.getContent().isBlank())
                    ? last.getContent()
                    : "请看看这张图片，并用你的性格自然回应。";
            MessageDto imageDto = new MessageDto();
            imageDto.setRole("user");
            imageDto.setContent(text);
            imageDto.setImageUrl(imageUrl);
            messages.add(buildVisionUserMessage(imageDto));
        }
        return messages;
    }

    private boolean isPlatformProvider(String provider) {
        return provider == null
                || provider.isBlank()
                || AiConstants.PLATFORM_PROVIDER.equalsIgnoreCase(provider);
    }

    private boolean isPlatformVault(VaultEntryResponse vault) {
        return vault != null && AiConstants.PLATFORM_PROVIDER.equalsIgnoreCase(vault.getProvider());
    }

    private String resolveModel(AiChatRequest request, VaultEntryResponse vault) {
        return resolveChatModel(request.getModel(), vault);
    }

    /**
     * 模型优先级：请求指定 > Vault（含 DB DEFAULT 池 model_default）> 环境变量 OPENAI_CHAT_MODEL。
     */
    private String resolveChatModel(String requestModel, VaultEntryResponse vault) {
        if (requestModel != null && !requestModel.isBlank()) {
            return requestModel.trim();
        }
        if (vault != null && vault.getModelDefault() != null && !vault.getModelDefault().isBlank()) {
            return vault.getModelDefault().trim();
        }
        if (defaultModel != null && !defaultModel.isBlank()) {
            return defaultModel.trim();
        }
        throw new BusinessException(ErrorCode.BAD_REQUEST,
                "未配置默认模型：请在 api_key_vault.model_default 或 OPENAI_CHAT_MODEL 中设置");
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
            String script =
                    "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end";
            redisTemplate.execute(
                    new DefaultRedisScript<>(script, Long.class),
                    Collections.singletonList(lockKey),
                    lockValue);
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

    private Prompt buildPrompt(AiChatRequest request, VaultEntryResponse vault, List<Message> messages) {
        double temperature = request.getTemperature() != null ? request.getTemperature() : 0.8;
        String model = resolveModel(request, vault);
        List<ToolCallback> toolCallbacks = toolManager.resolveToolCallbacks(request);

        if (ApiKeyVaultService.isOllamaEndpoint(vault.getBaseUrl())) {
            OllamaOptions.Builder builder = OllamaOptions.builder()
                    .model(model)
                    .temperature(temperature);
            applyToolCallbacks(builder, toolCallbacks);
            return new Prompt(messages, builder.build());
        }
        OpenAiChatOptions.Builder builder = OpenAiChatOptions.builder()
                .model(model)
                .temperature(temperature);
        applyToolCallbacks(builder, toolCallbacks);
        return new Prompt(messages, builder.build());
    }

    private static void applyToolCallbacks(OllamaOptions.Builder builder, List<ToolCallback> toolCallbacks) {
        if (!toolCallbacks.isEmpty()) {
            builder.toolCallbacks(toolCallbacks).internalToolExecutionEnabled(true);
        }
    }

    private static void applyToolCallbacks(OpenAiChatOptions.Builder builder, List<ToolCallback> toolCallbacks) {
        if (!toolCallbacks.isEmpty()) {
            builder.toolCallbacks(toolCallbacks).internalToolExecutionEnabled(true);
        }
    }

    private <T> T withChatToolScope(Long userId, AiChatRequest request, java.util.concurrent.Callable<T> action)
            throws Exception {
        if (request.getChatToolCharacterId() != null) {
            ChatToolContext.set(userId, request.getChatToolCharacterId(), request.getToolCharacterSettings());
        }
        try {
            return action.call();
        } finally {
            ChatToolContext.clear();
        }
    }

    private void runWithChatToolScope(Long userId, AiChatRequest request, Runnable action) throws Exception {
        withChatToolScope(userId, request, () -> {
            action.run();
            return null;
        });
    }

    private Prompt buildGenerationPrompt(VaultEntryResponse vault, String model, List<Message> messages) {
        if (ApiKeyVaultService.isOllamaEndpoint(vault.getBaseUrl())) {
            return new Prompt(messages, OllamaOptions.builder()
                    .model(model)
                    .temperature(0.6)
                    .build());
        }
        return new Prompt(messages, OpenAiChatOptions.builder()
                .model(model)
                .temperature(0.6)
                .build());
    }

    private String extractJsonObject(String text) {
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return text;
    }

    private String valueOrDefault(JsonNode root, String field, String defaultValue) {
        JsonNode node = root.get(field);
        if (node == null || node.isNull()) {
            return defaultValue;
        }
        String value = node.asText("");
        return value.isBlank() ? defaultValue : value.trim();
    }

    private String simplifyGenerationError(Exception e) {
        Throwable root = e;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        if (e instanceof ResourceAccessException
                || root instanceof java.nio.channels.UnresolvedAddressException
                || root instanceof java.net.UnknownHostException
                || root instanceof java.net.ConnectException) {
            return "连接失败（请检查 Base URL 是否可访问）";
        }
        if (e instanceof BusinessException be) {
            return be.getMessage();
        }
        return e.getMessage() != null ? e.getMessage() : "未知错误";
    }

    private List<Message> toSpringMessages(List<MessageDto> dtos) {
        if (dtos == null || dtos.isEmpty()) {
            return List.of();
        }
        List<Message> messages = new ArrayList<>();
        for (MessageDto dto : dtos) {
            boolean hasImage = dto.getImageUrl() != null && !dto.getImageUrl().isBlank();
            boolean hasContent = dto.getContent() != null && !dto.getContent().isBlank();
            if (!hasImage && !hasContent) {
                continue;
            }
            String role = dto.getRole() != null ? dto.getRole().toLowerCase() : "user";
            if (hasImage && "user".equals(role)) {
                messages.add(buildVisionUserMessage(dto));
                continue;
            }
            switch (role) {
                case "system" -> messages.add(new SystemMessage(dto.getContent()));
                case "assistant" -> messages.add(new AssistantMessage(dto.getContent()));
                default -> {
                    String raw = dto.getContent() != null ? dto.getContent() : "";
                    if (raw.contains("<user_message")) {
                        messages.add(new UserMessage(raw));
                    } else {
                        UserInputSanitizer.SanitizedUserText sanitized =
                                UserInputSanitizer.sanitizeChatMessage(raw);
                        messages.add(new UserMessage(sanitized.modelText()));
                    }
                }
            }
        }
        return messages;
    }

    private Message buildVisionUserMessage(MessageDto dto) {
        String objectKey = FileStorageService.extractObjectKey(dto.getImageUrl());
        if (objectKey == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "无效的图片地址");
        }
        byte[] bytes = fileStorageService.readObjectBytes(objectKey);
        String contentType = fileStorageService.resolveContentType(objectKey);
        String text = dto.getContent() != null && !dto.getContent().isBlank()
                ? dto.getContent()
                : "请看看这张图片，并用你的性格自然回应。";
        Media media = Media.builder()
                .mimeType(MimeTypeUtils.parseMimeType(contentType))
                .data(new ByteArrayResource(bytes))
                .build();
        return UserMessage.builder()
                .text(text)
                .media(media)
                .build();
    }

    /** 流式 chunk 末尾可能只有 usage 元数据，result 为 null */
    private static String extractStreamDelta(ChatResponse response) {
        if (response == null || response.getResult() == null) {
            return null;
        }
        var output = response.getResult().getOutput();
        if (output == null) {
            return null;
        }
        return output.getText();
    }

    private void sendSseChunk(SseEmitter emitter, String text) throws IOException {
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("content", text);
        emitter.send(SseEmitter.event().data(objectMapper.writeValueAsString(payload)));
    }

    private void sendSseReplace(SseEmitter emitter, String text) throws IOException {
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("replace", text);
        emitter.send(SseEmitter.event().data(objectMapper.writeValueAsString(payload)));
    }

    private String enforceExpectedLanguage(Long userId,
                                           AiChatRequest request,
                                           VaultEntryResponse vault,
                                           String model,
                                           ChatModel chatModel,
                                           String content) {
        String expected = request.getExpectedLanguage();
        if (expected == null || expected.isBlank() || content == null || content.isBlank()) {
            return content;
        }
        if (!OutputLanguageService.shouldEnforceLanguageGate(expected)) {
            return content;
        }
        if (outputLanguageService.matchesExpected(content, expected)) {
            return content;
        }

        String current = content;
        List<MessageDto> retryMessages = copyMessageDtos(request.getMessages());
        int retries = 0;
        while (retries < LANGUAGE_GATE_MAX_RETRIES) {
            log.info("Language gate retry {}: userId={}, expected={}", retries + 1, userId, expected);
            appendLanguageCorrectionMessages(retryMessages, current, expected);
            List<Message> messages = toSpringMessages(retryMessages);
            Prompt prompt = buildPrompt(request, vault, messages);
            ChatResponse response = chatModel.call(prompt);
            String regenerated = extractStreamDelta(response);
            if (regenerated == null || regenerated.isBlank()) {
                break;
            }
            current = regenerated;
            if (outputLanguageService.matchesExpected(current, expected)) {
                return current;
            }
            retries++;
        }
        log.warn("Language gate exhausted retries: userId={}, expected={}", userId, expected);
        return current;
    }

    private static List<MessageDto> copyMessageDtos(List<MessageDto> source) {
        List<MessageDto> copy = new ArrayList<>();
        if (source == null) {
            return copy;
        }
        for (MessageDto dto : source) {
            MessageDto item = new MessageDto();
            item.setRole(dto.getRole());
            item.setContent(dto.getContent());
            item.setImageUrl(dto.getImageUrl());
            copy.add(item);
        }
        return copy;
    }

    private static void appendLanguageCorrectionMessages(List<MessageDto> messages,
                                                         String assistantContent,
                                                         String expectedLang) {
        MessageDto assistant = new MessageDto();
        assistant.setRole("assistant");
        assistant.setContent(assistantContent);
        messages.add(assistant);

        MessageDto correction = new MessageDto();
        correction.setRole("user");
        correction.setContent(buildLanguageCorrectionInstruction(expectedLang));
        messages.add(correction);
    }

    private static String buildLanguageCorrectionInstruction(String expectedLang) {
        return switch (OutputLanguage.fromCode(expectedLang)) {
            case ZH -> "上一条回复用了英文，请用简体中文完整重写，意思不变，只改语言，不要解释。";
            case ZH_TW -> "上一條回覆用了英文，請用繁體中文完整重寫，意思不變，只改語言，不要解釋。";
            case JA -> "前の返信が英語になっていました。日本語で書き直してください。意味は変えず、言語だけ直してください。";
            case EN -> "Your last reply was not in English. Please rewrite entirely in English without explanation.";
        };
    }

    private void finishSseSuccess(SseEmitter emitter, String fullContent, StreamCallback callback) {
        try {
            if (callback != null) {
                callback.beforeStreamComplete(emitter, fullContent);
            }
            emitter.send(SseEmitter.event().data("[DONE]"));
            emitter.complete();
        } catch (IOException e) {
            log.warn("SSE complete failed", e);
            emitter.complete();
        }
        if (callback != null) {
            callback.onComplete(fullContent, null);
        }
    }

    private void finishSseError(SseEmitter emitter, String message, String partialContent,
                                StreamCallback callback) {
        try {
            Map<String, String> payload = new LinkedHashMap<>();
            payload.put("error", message);
            emitter.send(SseEmitter.event().data(objectMapper.writeValueAsString(payload)));
            emitter.send(SseEmitter.event().data("[DONE]"));
        } catch (Exception e) {
            log.debug("SSE error event send failed (emitter already completed): {}", e.getMessage());
        } finally {
            try {
                emitter.complete();
            } catch (Exception ignored) {}
        }
        if (callback != null) {
            Exception err = new BusinessException(ErrorCode.AI_PROVIDER_ERROR, message);
            callback.onComplete(partialContent, err);
        }
    }

    private String resolveStreamErrorMessage(Throwable e) {
        if (e instanceof BusinessException be) {
            return be.getMessage();
        }
        String msg = e.getMessage();
        return msg != null && !msg.isBlank() ? msg : "AI 服务调用失败";
    }

    private ChatModel buildChatModel(VaultEntryResponse vault, String model, String apiKey) {
        String baseUrl = vault.getBaseUrl();
        if (!ApiKeyVaultService.isOllamaEndpoint(baseUrl)) {
            String resolved = baseUrl != null && !baseUrl.isBlank() ? baseUrl : platformBaseUrl;
            OutboundUrlValidator.validateAndNormalize(resolved, false);
        }
        if (ApiKeyVaultService.isOllamaEndpoint(baseUrl)) {
            OllamaApi ollamaApi = OllamaApi.builder()
                    .baseUrl(baseUrl != null ? baseUrl : "http://localhost:11434")
                    .build();
            return OllamaChatModel.builder()
                    .ollamaApi(ollamaApi)
                    .defaultOptions(OllamaOptions.builder().model(model).build())
                    .build();
        }
        String resolvedUrl = normalizeOpenAiBaseUrl(baseUrl != null ? baseUrl : platformBaseUrl);
        OpenAiApi openAiApi = OpenAiApi.builder()
                .baseUrl(resolvedUrl)
                .apiKey(apiKey)
                .build();
        return OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(OpenAiChatOptions.builder().model(model).build())
                .build();
    }

    /** Spring AI 会自动追加 /v1，Base URL 不应以 /v1 结尾，否则会变成 /v1/v1/... 导致 404 */
    private String normalizeOpenAiBaseUrl(String baseUrl) {
        String resolved = (baseUrl != null && !baseUrl.isBlank()) ? baseUrl : platformBaseUrl;
        if (resolved == null || resolved.isBlank()) {
            throw new IllegalStateException(
                    "OpenAI-compatible base URL not configured. Set vault base_url or OPENAI_BASE_URL.");
        }
        baseUrl = resolved;
        String trimmed = baseUrl.replaceAll("/$", "");
        if (trimmed.endsWith("/v1")) {
            return trimmed.substring(0, trimmed.length() - 3);
        }
        return trimmed;
    }
}
