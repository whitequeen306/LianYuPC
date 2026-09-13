package com.lianyu.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.lianyu.common.base.ErrorCode;
import com.lianyu.common.constant.AiConstants;
import com.lianyu.common.exception.BusinessException;
import com.lianyu.common.util.TextUtils;
import com.lianyu.common.util.UserInputSanitizer;
import com.lianyu.service.dto.AiChatRequest;
import com.lianyu.service.dto.ChatResult;
import com.lianyu.service.dto.MessageDto;
import com.lianyu.service.dto.VaultEntryResponse;
import com.lianyu.service.storage.FileStorageService;
import com.lianyu.service.support.OutputLanguageService;
import com.lianyu.service.user.UserPublicProfileService;
import com.lianyu.service.user.UserSettingsResolver;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 视觉/多模态聊天链路（从 AiChatService 拆出）：
 * - {@link #observeDesktop}：桌面截图 VL 识图 → 角色语气主动问候；
 * - {@link #chatImageBlocking} / {@link #chatImageStream}：图片消息两段式（识图 → 文本模型）
 *   或跟随文本 Provider 的一次多模态调用（路由见 {@link #resolveVisionRoute}）。
 *
 * <p>依赖方向：Vision → AiChatService（共享 vault 解析/prompt 构建/工具作用域/语言门），
 * 反向无依赖。SSE 收尾统一走 {@link SseChatStreamHelper}。
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class VisionChatService {

    private static final long SSE_TIMEOUT_MS = 1_800_000L;

    private static final String VISION_ANALYSIS_JSON_INSTRUCTION = """

            你是图片分析助手，只输出 JSON，不输出 markdown，不输出解释。
            JSON 字段固定为：subIntent, confidence, imageDescription。
            - subIntent: 判断用户发送这张图片的子意图，如求识图/分享日常/展示成果/吐槽/求建议/闲聊。
            - confidence: 诚实表达看得清程度。看不清、模糊、遮挡、分辨率不足时必须明确写低置信。
            - imageDescription: 只写客观可见内容，不脑补，不扮演角色。
            输出格式示例：
            {"subIntent":"求识图","confidence":"high","imageDescription":"一只橘猫趴在窗台上"}
            """;

    private final AiChatService aiChatService;
    private final AiResilience resilience;
    private final SseChatStreamHelper sseHelper;
    private final VisionMessageBuilder visionMessageBuilder;
    private final VisionAnalysisParser visionAnalysisParser;
    private final ApiKeyVaultService vaultService;
    private final UserPublicProfileService userPublicProfileService;

    @Value("${lianyu.ai.multimodal.enabled:true}")
    private boolean multimodalEnabled;

    @Value("${lianyu.ai.multimodal.base-url:https://dashscope.aliyuncs.com/compatible-mode/v1}")
    private String multimodalBaseUrl;

    @Value("${lianyu.ai.multimodal.api-key:}")
    private String multimodalApiKey;

    @Value("${lianyu.ai.multimodal.model:qwen3.7-flash}")
    private String multimodalModel;

    @Value("${lianyu.ai.multimodal.max-tokens:800}")
    private int multimodalMaxTokens;

    @Value("${lianyu.ai.multimodal.describe-max-tokens:420}")
    private int multimodalDescribeMaxTokens;

    /**
     * 桌面感知：截图 VL 识图 → 角色语气生成主动问候。
     */
    public String observeDesktop(Long userId, String imageBase64, String windowTitle, String persona,
                                 String provider, String model) {
        if (!multimodalEnabled) {
            return null;
        }
        VaultEntryResponse visionVault = buildMultimodalVault();
        ChatModel visionChatModel = aiChatService.buildChatModel(visionVault, multimodalModel, visionVault.getApiKey());

        byte[] imageBytes;
        try {
            imageBytes = java.util.Base64.getDecoder().decode(imageBase64);
        } catch (IllegalArgumentException e) {
            log.warn("Desktop observe: invalid base64 image");
            return null;
        }

        // Stage 1: 视觉 JSON 分析
        Media media = Media.builder()
                .data(new ByteArrayResource(imageBytes))
                .mimeType(MimeTypeUtils.IMAGE_PNG)
                .build();
        Message vlMessage = UserMessage.builder()
                .text("请分析这张桌面截图，判断用户当前大概在做什么，并只输出结构化 JSON。"
                        + "若看不清必须如实说明。")
                .media(media)
                .build();
        VisionAnalysisResult analysis = analyzeImage(visionChatModel, multimodalModel, vlMessage, multimodalDescribeMaxTokens);
        if (analysis.imageDescription() == null || analysis.imageDescription().isBlank()) {
            log.warn("Desktop observe: vision analysis returned empty description");
            return null;
        }
        log.info("Desktop observe: vision result confidence={}, subIntent={}",
                analysis.confidence(), analysis.subIntent());

        // Stage 2: 用户当前文本模型生成桌宠问候（provider/model 由调用方携带，缺省走平台默认）
        String personaText = (persona != null && !persona.isBlank()) ? persona : "你是一个可爱的桌面宠物。";
        String winTitle = (windowTitle != null && !windowTitle.isBlank()) ? windowTitle : "未知";
        VaultEntryResponse textVault = aiChatService.resolveVault(userId,
                (provider != null && !provider.isBlank()) ? provider : null);
        String textModel = (model != null && !model.isBlank()) ? model.trim()
                : aiChatService.resolveChatModel(null, textVault);
        String textApiKey = aiChatService.resolveApiKeyForProvider(textVault);
        aiChatService.logChatVaultUsage(userId, null, textVault, textModel, "desktop-observe");
        ChatModel textChatModel = aiChatService.buildChatModel(textVault, textModel, textApiKey);
        String greetingPrompt = buildDesktopGreetingPrompt(personaText, winTitle, analysis);

        List<Message> greetingMessages = List.of(new UserMessage(greetingPrompt));
        Prompt greetingPromptObj = aiChatService.buildGenerationPrompt(textVault, textModel, greetingMessages);
        ChatResponse greetingResponse = textChatModel.call(greetingPromptObj);
        String greeting = AiChatService.extractStreamDelta(greetingResponse);
        if (greeting == null || greeting.isBlank()) {
            log.warn("Desktop observe: greeting generation returned empty");
            return null;
        }
        log.info("Desktop observe: greeting generated ({} chars): {}", greeting.length(), greeting);
        return greeting.trim();
    }

    /** 视觉模型 Vault（DashScope 官方 OpenAI-compatible url/key，默认 qwen3.7-flash）。 */
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
        final var lane = resilience.resolveBulkhead(request);
        try {
            return lane.executeCallable(() -> {
                VaultEntryResponse vault = aiChatService.resolveVaultForRequest(userId, request);
                return resilience.resolveBreaker(vault).executeCallable(() -> doImageChat(userId, request));
            });
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
     * 这里在调度线程内完成阻塞调用后，把回复以 SSE chunk 形式下发，复用 {@link AiChatService.StreamCallback}
     * 保证后处理（pieces 拆分、落库、关系/情绪更新）与纯文本链路一致。
     */
    public SseEmitter chatImageStream(Long userId, AiChatRequest request, AiChatService.StreamCallback callback) {
        final var lane = resilience.interactiveBulkhead();
        if (!lane.tryAcquirePermission()) {
            throw new BusinessException(ErrorCode.AI_RATE_LIMITED, "对话服务繁忙，请稍后再试");
        }
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        try {
            CompletableFuture.runAsync(() -> {
                try {
                    VaultEntryResponse vault = aiChatService.resolveVaultForRequest(userId, request);
                    ChatResult result = resilience.resolveBreaker(vault).executeCallable(() -> doImageChat(userId, request));
                    if (callback != null) {
                        callback.onVisionComplete(result.getImageDescription());
                    }
                    String reply = result.getContent();
                    if (reply != null && !reply.isBlank()) {
                        sseHelper.sendSseChunk(emitter, reply);
                    }
                    sseHelper.finishSseSuccess(emitter, reply, callback);
                } catch (Exception e) {
                    log.error("Multimodal stream error: userId={}", userId, e);
                    sseHelper.finishSseError(emitter, sseHelper.resolveStreamErrorMessage(e), "", callback);
                } finally {
                    lane.releasePermission();
                }
            }, resilience.resolveAiExecutor(null));
        } catch (RejectedExecutionException e) {
            lane.releasePermission();
            log.warn("AI stream executor saturated, reject chatImageStream userId={}", userId);
            throw new BusinessException(ErrorCode.AI_RATE_LIMITED, "对话服务繁忙，请稍后再试");
        }
        return emitter;
    }

    private ChatResult doImageChat(Long userId, AiChatRequest request) throws Exception {
        if (!multimodalEnabled) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "图片识别功能未启用");
        }
        VisionRoute vision = resolveVisionRoute(userId, request);
        if (vision.oneCall()) {
            return doOneCallImageChat(userId, request, vision);
        }
        String visionApiKey = aiChatService.resolveApiKeyForProvider(vision.vault());
        ChatModel visionChatModel = aiChatService.buildChatModel(vision.vault(), vision.model(), visionApiKey);
        aiChatService.logChatVaultUsage(userId, request.getProvider(), vision.vault(), vision.model(), "image-vision");
        MessageDto imageDto = lastImageMessage(request.getMessages(), request.getImageUrl());
        VisionAnalysisResult analysis;
        try {
            analysis = analyzeImage(
                    visionChatModel,
                    vision.model(),
                    visionMessageBuilder.buildVisionAnalysisUserMessage(imageDto),
                    multimodalMaxTokens);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw mapVisionProviderException(e);
        }

        VaultEntryResponse textVault = aiChatService.resolveVaultForRequest(userId, request);
        String textModel = aiChatService.resolveModel(request, textVault);
        String textApiKey = aiChatService.resolveApiKeyForProvider(textVault);
        aiChatService.logChatVaultUsage(userId, request.getProvider(), textVault, textModel, "image-text");
        ChatModel textChatModel = aiChatService.buildChatModel(textVault, textModel, textApiKey);
        List<MessageDto> textDtos = buildImageAugmentedTextMessageDtos(request.getMessages(), analysis);
        List<Message> messages = toTextOnlySpringMessages(textDtos);
        Prompt prompt = aiChatService.buildPrompt(request, textVault, messages);

        // 第二阶段须与主聊天链路一致：进 ChatToolContext + 走语言门控，
        // 重试 seed 用文本化后的 dtos（已无图片 media），避免重生成时再带图。
        return aiChatService.withChatToolScope(userId, request, () -> {
            ChatResponse response = textChatModel.call(prompt);
            String raw = AiChatService.extractStreamDelta(response);
            if (raw == null || raw.isBlank()) {
                throw new BusinessException(ErrorCode.AI_PROVIDER_ERROR, "图片识别失败，请换一张图片再试");
            }
            String reply = aiChatService.enforceExpectedLanguage(userId, request, textVault, textModel, textChatModel,
                    raw, null, textDtos);
            log.info("Multimodal chat: userId={}, visionModel={}, subIntent={}, confidence={}, low={}",
                    userId, vision.model(), analysis.subIntent(), analysis.confidence(),
                    VisionAnalysisParser.isLowConfidence(analysis.confidence()));
            ChatResult.ChatResultBuilder builder = ChatResult.builder()
                    .content(reply)
                    .imageDescription(analysis.imageDescription());
            if (response.getMetadata() != null && response.getMetadata().getUsage() != null) {
                var usage = response.getMetadata().getUsage();
                builder.totalTokens(usage.getTotalTokens() != null ? usage.getTotalTokens().intValue() : null);
            }
            return builder.build();
        });
    }

    /**
     * 跟随文本 Provider 的一次多模态调用：角色 system prompt + 历史 + 图片 inline base64
     * 直接发给用户的多模态模型（如 gemini-3.6-flash），一次调用出角色回复。
     * 无独立识图描述——历史占位走通用文案（ImageMessageHistoryText 兜底）。
     * 模型不收图片时（unknown variant image_url 等）由 mapVisionProviderException 给出可读报错。
     */
    private ChatResult doOneCallImageChat(Long userId, AiChatRequest request, VisionRoute route) throws Exception {
        VaultEntryResponse vault = route.vault();
        String model = route.model();
        String apiKey = aiChatService.resolveApiKeyForProvider(vault);
        ChatModel chatModel = aiChatService.buildChatModel(vault, model, apiKey);
        aiChatService.logChatVaultUsage(userId, request.getProvider(), vault, model, "image-onecall");
        MessageDto imageDto = lastImageMessage(request.getMessages(), request.getImageUrl());
        List<Message> messages = buildMultimodalMessages(request.getMessages(), imageDto.getImageUrl());
        Prompt prompt = aiChatService.buildPrompt(request, vault, messages);
        return aiChatService.withChatToolScope(userId, request, () -> {
            ChatResponse response;
            try {
                response = chatModel.call(prompt);
            } catch (BusinessException e) {
                throw e;
            } catch (Exception e) {
                throw mapVisionProviderException(e);
            }
            String raw = AiChatService.extractStreamDelta(response);
            if (raw == null || raw.isBlank()) {
                throw new BusinessException(ErrorCode.AI_PROVIDER_ERROR, "图片识别失败，请换一张图片再试");
            }
            String reply = aiChatService.enforceExpectedLanguage(userId, request, vault, model, chatModel, raw, null, null);
            log.info("Multimodal one-call chat: userId={}, model={}", userId, model);
            ChatResult.ChatResultBuilder builder = ChatResult.builder()
                    .content(reply)
                    .imageDescription(null);
            if (response.getMetadata() != null && response.getMetadata().getUsage() != null) {
                var usage = response.getMetadata().getUsage();
                builder.totalTokens(usage.getTotalTokens() != null ? usage.getTotalTokens().intValue() : null);
            }
            return builder.build();
        });
    }

    /**
     * 识图路由 v2（用户全局设置 visionSource）：
     * - platform（默认）：平台多模态 vault（qwen3.7-flash），两段式（识图 → 文本模型回复）；
     * - followText：跟随请求的文本 Provider 一次调用（要求该模型支持图片输入）；
     * - provider：指定的 purpose=vision 识图 vault 做第一段，第二段仍走文本 Provider。
     * 请求里的 visionModel 与 vault 的 visionModelDefault 仅为兼容旧客户端而保留，均不再生效。
     */
    private VisionRoute resolveVisionRoute(Long userId, AiChatRequest request) {
        var settings = userPublicProfileService.getMySettings(userId);
        String mode = settings.getVisionSourceMode();
        if (UserSettingsResolver.VISION_MODE_FOLLOW_TEXT.equals(mode)) {
            VaultEntryResponse textVault = aiChatService.resolveVaultForRequest(userId, request);
            return new VisionRoute(textVault, aiChatService.resolveModel(request, textVault), true);
        }
        if (UserSettingsResolver.VISION_MODE_PROVIDER.equals(mode)) {
            VaultEntryResponse visionVault = vaultService.resolveVisionVault(userId, settings.getVisionSourceProvider());
            if (visionVault != null) {
                return new VisionRoute(visionVault, visionVault.getModelDefault(), false);
            }
            log.warn("Vision source provider unavailable, fallback to platform: userId={}, provider={}",
                    userId, settings.getVisionSourceProvider());
        }
        return new VisionRoute(buildMultimodalVault(), multimodalModel, false);
    }

    private record VisionRoute(VaultEntryResponse vault, String model, boolean oneCall) {}

    private BusinessException mapVisionProviderException(Throwable e) {
        String msg = TextUtils.collectThrowableMessages(e);
        String lower = msg.toLowerCase();
        if (lower.contains("data_inspection_failed") || lower.contains("inappropriate content")) {
            return new BusinessException(ErrorCode.AI_PROVIDER_ERROR,
                    "图片未能通过内容安全审核，请换一张图片再试");
        }
        // 自有 Provider 的 base-url 是纯文本接口（如 DeepSeek）却配了识图模型：
        // 上游会以「image_url 非法」拒绝，翻译成可操作的指引
        if (lower.contains("unknown variant `image_url`")
                || lower.contains("does not support image")
                || lower.contains("not support image")
                || lower.contains("vision is not supported")) {
            return new BusinessException(ErrorCode.AI_PROVIDER_ERROR,
                    "当前模型不支持图片输入：请到设置把「识图来源」改为平台默认或识图 Provider，或换一个多模态模型");
        }
        if (lower.contains("无法连接") || lower.contains("connection") || lower.contains("timed out")
                || lower.contains("timeout") || lower.contains("connect timed out")
                || lower.contains("connection refused") || lower.contains("unknown host")) {
            return new BusinessException(ErrorCode.AI_PROVIDER_ERROR,
                    "识图服务暂时无法连接，请稍后重试");
        }
        if (e instanceof BusinessException be) {
            return be;
        }
        log.warn("Vision provider error: {}", msg);
        return new BusinessException(ErrorCode.AI_PROVIDER_ERROR, "图片识别失败，请换一张图片再试");
    }

    /**
     * 组装多模态 prompt：system(角色 prompt + 结构化JSON指令) + 历史(纯文本) + 末尾用户消息(图+文字)。
     * 图片从 MinIO 读出转 inline base64（中转服务拉不到相对路径），复用 {@link VisionMessageBuilder}。
     */
    private List<Message> buildMultimodalMessages(List<MessageDto> dtos, String imageUrl) {
        if (dtos == null || dtos.isEmpty()) {
            return List.of();
        }
        List<Message> messages = new ArrayList<>();
        int start = 0;
        if ("system".equalsIgnoreCase(dtos.get(0).getRole())) {
            String sysContent = dtos.get(0).getContent();
            messages.add(new SystemMessage(sysContent != null ? sysContent : ""));
            start = 1;
        }
        if (start < dtos.size() - 1) {
            messages.addAll(aiChatService.toSpringMessages(dtos.subList(start, dtos.size() - 1)));
        }
        if (start < dtos.size()) {
            MessageDto last = dtos.get(dtos.size() - 1);
            String text = (last.getContent() != null && !last.getContent().isBlank())
                    ? last.getContent()
                    : VisionMessageBuilder.VISION_ANALYSIS_USER_HINT;
            MessageDto imageDto = new MessageDto();
            imageDto.setRole("user");
            imageDto.setContent(text);
            imageDto.setImageUrl(imageUrl);
            messages.add(visionMessageBuilder.buildVisionUserMessage(imageDto));
        }
        return messages;
    }

    private VisionAnalysisResult analyzeImage(
            ChatModel chatModel, String visionModel, Message visionUserMessage, int maxTokens) {
        String model = (visionModel != null && !visionModel.isBlank()) ? visionModel.trim() : multimodalModel;
        List<Message> messages = List.of(new SystemMessage(VISION_ANALYSIS_JSON_INSTRUCTION), visionUserMessage);
        Prompt prompt = new Prompt(messages, OpenAiChatOptions.builder()
                .model(model)
                .temperature(0.1)
                .maxTokens(maxTokens)
                .build());
        ChatResponse response = chatModel.call(prompt);
        String raw = AiChatService.extractStreamDelta(response);
        if (raw == null || raw.isBlank()) {
            throw new BusinessException(ErrorCode.AI_PROVIDER_ERROR, "图片识别失败，请换一张清晰点的图片再试");
        }
        try {
            return visionAnalysisParser.parse(raw);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.AI_PROVIDER_ERROR, "图片识别失败，请换一张清晰点的图片再试");
        }
    }

    private MessageDto lastImageMessage(List<MessageDto> dtos, String imageUrl) {
        if (dtos == null || dtos.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "缺少图片消息");
        }
        MessageDto last = dtos.get(dtos.size() - 1);
        MessageDto copy = new MessageDto();
        copy.setRole((last.getRole() != null && !last.getRole().isBlank()) ? last.getRole() : "user");
        copy.setContent(last.getContent());
        copy.setImageUrl((last.getImageUrl() != null && !last.getImageUrl().isBlank()) ? last.getImageUrl() : imageUrl);
        if (copy.getImageUrl() == null || copy.getImageUrl().isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "无效的图片地址");
        }
        return copy;
    }

    private List<MessageDto> buildImageAugmentedTextMessageDtos(List<MessageDto> dtos, VisionAnalysisResult analysis) {
        List<MessageDto> out = new ArrayList<>();
        int start = 0;
        if (dtos != null && !dtos.isEmpty() && "system".equalsIgnoreCase(dtos.get(0).getRole())) {
            String sysContent = dtos.get(0).getContent();
            MessageDto sys = new MessageDto();
            sys.setRole("system");
            sys.setContent(((sysContent != null) ? sysContent : "") + "\n\n" + buildImageAnalysisAugmentation(analysis));
            out.add(sys);
            start = 1;
        } else {
            MessageDto sys = new MessageDto();
            sys.setRole("system");
            sys.setContent(buildImageAnalysisAugmentation(analysis));
            out.add(sys);
        }
        if (dtos != null) {
            int lastIdx = dtos.size() - 1;
            for (int i = start; i < lastIdx; i++) {
                MessageDto src = dtos.get(i);
                if (src.getContent() == null || src.getContent().isBlank()) {
                    continue;
                }
                MessageDto copy = new MessageDto();
                copy.setRole(src.getRole());
                copy.setContent(src.getContent());
                out.add(copy);
            }
            if (start <= lastIdx) {
                MessageDto last = dtos.get(lastIdx);
                MessageDto user = new MessageDto();
                user.setRole("user");
                user.setContent(resolveImageTurnUserText(last.getContent(), analysis));
                out.add(user);
            }
        }
        return out;
    }

    /**
     * 文本阶段用户句：纯图片/空 user_message 时显式带上识图描述，避免角色当成「空消息」。
     */
    private String resolveImageTurnUserText(String rawContent, VisionAnalysisResult analysis) {
        String description = analysis != null && analysis.imageDescription() != null
                ? analysis.imageDescription().trim() : "";
        String stripped = stripUserMessageXml(rawContent);
        if (stripped == null || stripped.isBlank() || VisionMessageBuilder.isImagePlaceholderContent(stripped)
                || VisionMessageBuilder.isImagePlaceholderContent(rawContent)) {
            String descLine = description.isBlank()
                    ? "（识图未给出清晰描述，请自然回应用户发来的图片。）"
                    : "图片内容：" + description;
            return UserInputSanitizer.wrapStoredTextForModel(
                    "我发了一张图片。\n" + descLine + "\n请结合图片内容，用你的性格自然回应，不要当成空消息。");
        }
        if (rawContent != null && rawContent.contains("<user_message")) {
            return rawContent;
        }
        return UserInputSanitizer.wrapStoredTextForModel(stripped);
    }

    private static String stripUserMessageXml(String raw) {
        if (raw == null) {
            return null;
        }
        return raw.replaceAll("(?s)<user_message[^>]*>|</user_message>", "").trim();
    }

    private String buildImageAnalysisAugmentation(VisionAnalysisResult analysis) {
        String subIntent = analysis != null && analysis.subIntent() != null ? analysis.subIntent() : "未知";
        String confidence = analysis != null && analysis.confidence() != null ? analysis.confidence() : "unknown";
        String description = analysis != null && analysis.imageDescription() != null ? analysis.imageDescription() : "";
        return "[图片分析结果]\n"
                + "- 子意图: " + subIntent + "\n"
                + "- 可辨识度: " + confidence + "\n"
                + "- 客观描述: " + description + "\n\n"
                + "规则:\n"
                + "1. 用户本轮发送了图片；必须结合上方「客观描述」回应，禁止当成空消息或没发内容。\n"
                + "2. 若可辨识度低，必须如实告诉用户这张图看不太清，不要假装看到细节。\n"
                + "3. 保持角色语气、人设、关系状态。\n"
                + "4. 不要输出 JSON。";
    }

    private String buildDesktopGreetingPrompt(String persona, String windowTitle, VisionAnalysisResult analysis) {
        String personaText = (persona != null && !persona.isBlank()) ? persona : "你是一个可爱的桌面宠物。";
        String winTitle = (windowTitle != null && !windowTitle.isBlank()) ? windowTitle : "未知";
        String description = analysis != null && analysis.imageDescription() != null ? analysis.imageDescription() : "";
        String confidence = analysis != null && analysis.confidence() != null ? analysis.confidence() : "unknown";
        return personaText + "\n\n"
                + "你正在看着用户的电脑屏幕。当前画面：" + description + "\n"
                + "图像可辨识度：" + confidence + "\n"
                + "用户正在使用的窗口：" + winTitle + "\n\n"
                + "如果图像可辨识度低，请自然承认看不太清。"
                + "请用你的角色语气，对用户正在做的事情说一句话。\n"
                + "要求：自然、口语化、不超过40字。不要加括号或动作描写。";
    }

    private List<Message> toTextOnlySpringMessages(List<MessageDto> dtos) {
        if (dtos == null || dtos.isEmpty()) {
            return List.of();
        }
        List<Message> messages = new ArrayList<>();
        for (MessageDto dto : dtos) {
            boolean hasContent = dto.getContent() != null && !dto.getContent().isBlank();
            if (!hasContent) {
                continue;
            }
            String role = dto.getRole() != null ? dto.getRole().toLowerCase() : "user";
            switch (role) {
                case "system" -> messages.add(new SystemMessage(dto.getContent()));
                case "assistant" -> messages.add(new AssistantMessage(dto.getContent()));
                default -> {
                    String raw = dto.getContent() != null ? dto.getContent() : "";
                    if (raw.contains("<user_message")) {
                        messages.add(new UserMessage(raw));
                    } else {
                        UserInputSanitizer.SanitizedUserText sanitized = UserInputSanitizer.sanitizeChatMessage(raw);
                        messages.add(new UserMessage(sanitized.modelText()));
                    }
                }
            }
        }
        return messages;
    }
}
