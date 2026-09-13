package com.lianyu.service.conversation;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lianyu.common.base.ErrorCode;
import com.lianyu.common.exception.BusinessException;
import com.lianyu.common.util.UserInputSanitizer;
import com.lianyu.dao.entity.Character;
import com.lianyu.dao.entity.CharacterSquareTemplate;
import com.lianyu.dao.entity.Conversation;
import com.lianyu.dao.entity.GroupMember;
import com.lianyu.dao.entity.Message;
import com.lianyu.dao.entity.User;
import com.lianyu.dao.mapper.CharacterMapper;
import com.lianyu.dao.mapper.CharacterSquareTemplateMapper;
import com.lianyu.dao.mapper.ConversationMapper;
import com.lianyu.dao.mapper.GroupMemberMapper;
import com.lianyu.dao.mapper.MessageMapper;
import com.lianyu.dao.mapper.UserMapper;
import com.lianyu.ai.graph.ChatTurnScene;
import com.lianyu.service.ai.AiChatService;
import com.lianyu.service.ai.ApiKeyVaultService;
import com.lianyu.service.ai.AssistantReplyService;
import com.lianyu.service.ai.CharacterPromptBuilder;
import com.lianyu.service.ai.PetMeetVoiceCatalog;
import com.lianyu.service.ai.PetVoiceRegistry;
import com.lianyu.service.graph.ChatTurnCommand;
import com.lianyu.service.graph.ChatTurnFacade;
import com.lianyu.service.graph.ChatTurnResult;
import com.lianyu.service.graph.ImageMessageHistoryText;
import com.lianyu.service.character.CharacterChatBehavior;
import com.lianyu.service.character.CharacterChatBehaviorResolver;
import com.lianyu.service.ai.InnerThoughtFilter;
import com.lianyu.service.character.CharacterPreferenceResolver;
import com.lianyu.service.character.CharacterStateService;
import com.lianyu.service.character.UserAddressingResolver;
import com.lianyu.service.dto.*;
import com.lianyu.service.memory.MemoryRetriever;
import com.lianyu.service.memory.MemoryWriter;
import com.lianyu.service.notification.NotificationService;
import com.lianyu.service.relationship.RelationshipStateService;
import com.lianyu.service.storage.FileStorageService;
import com.lianyu.service.support.OutputLanguageService;
import com.lianyu.service.tools.ChatToolContext;
import com.lianyu.service.tools.TimeTool;
import java.io.IOException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationService {

    private static final String SEQ_KEY_PREFIX = "msg_seq:";
    private static final String GROUP_TURN_KEY_PREFIX = "group_chat:turn:";
    private final ConversationMapper conversationMapper;
    private final MessageMapper messageMapper;
    private final GroupMemberMapper groupMemberMapper;
    private final CharacterMapper characterMapper;
    private final AiChatService aiChatService;
    private final ApiKeyVaultService apiKeyVaultService;
    private final ChatTurnFacade chatTurnFacade;
    private final CharacterPromptBuilder promptBuilder;
    private final MemoryWriter memoryWriter;
    private final StringRedisTemplate redisTemplate;
    private final FileStorageService fileStorageService;
    private final CharacterChatBehaviorResolver chatBehaviorResolver;
    private final AssistantReplyService assistantReplyService;
    private final ObjectMapper objectMapper;
    private final NotificationService notificationService;
    private final OutputLanguageService outputLanguageService;
    private final CharacterStateService characterStateService;
    private final RelationshipStateService relationshipStateService;
    private final ProactiveUnrepliedThrottle proactiveUnrepliedThrottle;
    private final TimeTool timeTool;
    private final SessionSummaryService sessionSummaryService;

    @Lazy
    @Autowired
    private SingleChatOpeningScheduler singleChatOpeningScheduler;

    @Value("${lianyu.ai.context-window:20}")
    private int contextWindow;

    @Transactional
    public ConversationResponse create(Long userId, CreateConversationRequest request) {
        Character character = characterMapper.selectById(request.getCharacterId());
        if (character == null || !character.getOwnerUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.CHARACTER_NOT_FOUND);
        }
        ensureCharacterNotBlocked(character);

        String mode = request.getMode().toUpperCase();
        if ("SINGLE".equals(mode)) {
            Conversation existing = conversationMapper.selectOne(new LambdaQueryWrapper<Conversation>()
                    .eq(Conversation::getUserId, userId)
                    .eq(Conversation::getMode, "SINGLE")
                    .eq(Conversation::getCharacterId, request.getCharacterId())
                    .orderByDesc(Conversation::getCreatedAt)
                    .last("LIMIT 1"));
            if (existing != null) {
                log.info("Reuse existing single conversation: id={}, characterId={}, userId={}",
                        existing.getId(), request.getCharacterId(), userId);
                return toResponse(existing, character);
            }
        }

        Conversation conversation = new Conversation();
        conversation.setUserId(userId);
        conversation.setCharacterId(request.getCharacterId());
        conversation.setMode(mode);
        conversation.setTitle(character.getName());
        try {
            conversationMapper.insert(conversation);
        } catch (DuplicateKeyException e) {
            if ("SINGLE".equals(mode)) {
                Conversation existing = conversationMapper.selectOne(new LambdaQueryWrapper<Conversation>()
                        .eq(Conversation::getUserId, userId)
                        .eq(Conversation::getMode, "SINGLE")
                        .eq(Conversation::getCharacterId, request.getCharacterId())
                        .orderByDesc(Conversation::getCreatedAt)
                        .last("LIMIT 1"));
                if (existing != null) {
                    log.info("Reuse existing single conversation after conflict: id={}, characterId={}, userId={}",
                            existing.getId(), request.getCharacterId(), userId);
                    return toResponse(existing, character);
                }
            }
            throw e;
        }

        log.info("Conversation created: id={}, characterId={}, userId={}", conversation.getId(), request.getCharacterId(), userId);
        if ("SINGLE".equalsIgnoreCase(mode)) {
            Long convId = conversation.getId();
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        singleChatOpeningScheduler.startSequence(userId, convId);
                    }
                });
            } else {
                singleChatOpeningScheduler.startSequence(userId, convId);
            }
        }
        return toResponse(conversation, character);
    }

    public List<ConversationResponse> list(Long userId) {
        List<Conversation> conversations = conversationMapper.selectList(new LambdaQueryWrapper<Conversation>()
                .eq(Conversation::getUserId, userId)
                .orderByDesc(Conversation::getCreatedAt)
                .last("LIMIT 200"));

        if (conversations.isEmpty()) {
            return List.of();
        }

        Set<Long> characterIds = conversations.stream()
                .map(Conversation::getCharacterId)
                .filter(id -> id != null && id > 0)
                .collect(Collectors.toSet());
        Map<Long, Character> characterMap = new HashMap<>();
        if (!characterIds.isEmpty()) {
            characterMapper.selectByIds(characterIds).forEach(c -> characterMap.put(c.getId(), c));
        }

        List<Long> convIds = conversations.stream().map(Conversation::getId).toList();
        Map<Long, String> lastMessageMap = buildLastMessageSnippetMap(convIds);
        Map<Long, String> lastCharacterMessageMap = buildLastCharacterMessageSnippetMap(convIds);

        List<ConversationResponse> result = new ArrayList<>();
        for (Conversation conv : conversations) {
            Character character = conv.getCharacterId() == null ? null : characterMap.get(conv.getCharacterId());
            String lastMsg = lastMessageMap.get(conv.getId());
            String lastCharacterMsg = lastCharacterMessageMap.get(conv.getId());
            result.add(toResponse(conv, character, lastMsg, lastCharacterMsg));
        }
        return result;
    }

    public ConversationResponse get(Long userId, Long conversationId) {
        Conversation conversation = findOwned(userId, conversationId);
        Character character = null;
        if (conversation.getCharacterId() != null) {
            character = characterMapper.selectById(conversation.getCharacterId());
        }
        Map<Long, String> lastMessageMap = buildLastMessageSnippetMap(List.of(conversationId));
        Map<Long, String> lastCharacterMessageMap = buildLastCharacterMessageSnippetMap(List.of(conversationId));
        return toResponse(
                conversation,
                character,
                lastMessageMap.get(conversationId),
                lastCharacterMessageMap.get(conversationId)
        );
    }

    @Transactional
    public void delete(Long userId, Long conversationId) {
        Conversation conversation = findOwned(userId, conversationId);
        messageMapper.delete(new LambdaQueryWrapper<Message>()
                .eq(Message::getConversationId, conversationId));
        if ("GROUP".equalsIgnoreCase(conversation.getMode())) {
            groupMemberMapper.delete(new LambdaQueryWrapper<GroupMember>()
                    .eq(GroupMember::getConversationId, conversationId));
        }
        notificationService.deleteForConversation(userId, conversationId);
        conversationMapper.deleteById(conversationId);
        redisTemplate.delete(List.of(
                SEQ_KEY_PREFIX + conversationId,
                GROUP_TURN_KEY_PREFIX + conversationId
        ));
        sessionSummaryService.invalidate(conversationId);
        log.info("Conversation deleted: id={}, mode={}", conversationId, conversation.getMode());
    }

    /**
     * 清空会话消息但保留会话行（保持会话 ID 稳定）。
     * 删除该会话全部消息、失效会话摘要缓存、重置消息序号；会话行本身不动。
     * 用于「清空聊天记录」：外部绑定（如 QQ 桥接按 conversationId 路由）不会因清空而 404。
     */
    @Transactional
    public void clearMessages(Long userId, Long conversationId) {
        Conversation conversation = findOwned(userId, conversationId);
        messageMapper.delete(new LambdaQueryWrapper<Message>()
                .eq(Message::getConversationId, conversationId));
        redisTemplate.delete(SEQ_KEY_PREFIX + conversationId);
        if ("GROUP".equalsIgnoreCase(conversation.getMode())) {
            redisTemplate.delete(GROUP_TURN_KEY_PREFIX + conversationId);
        }
        sessionSummaryService.invalidate(conversationId);
        log.info("Conversation messages cleared (row kept): id={}, mode={}", conversationId, conversation.getMode());
    }

    public MessageResponse sendMessage(Long userId, Long conversationId, SendMessageRequest request) {
        requireUserTextProvider(request);
        Conversation conversation = findOwned(userId, conversationId);
        Character character = characterMapper.selectById(conversation.getCharacterId());
        if (character == null) {
            throw new BusinessException(ErrorCode.CHARACTER_NOT_FOUND);
        }
        ensureCharacterNotBlocked(character);

        long nextSeq = getNextSeq(conversationId);
        PreparedUserTurn turn = prepareUserTurn(conversationId, character.getId(), request, nextSeq);
        messageMapper.insert(turn.userMsg());
        proactiveUnrepliedThrottle.resetOnUserReply(conversationId);
        List<Message> history = getRecentMessages(conversationId, contextWindow);
        relationshipStateService.recordUserTurn(
                userId,
                character.getId(),
                conversationId,
                turn.userMsg(),
                history);

        // 更新角色情绪
        characterStateService.afterUserMessage(character.getId(), userId, turn.aiUserContent());

        boolean hasImage = turn.userMsg().getImageUrl() != null && !turn.userMsg().getImageUrl().isBlank();
        ChatTurnResult chatResult = chatTurnFacade.invokeBlocking(ChatTurnCommand.builder()
                .scene(ChatTurnScene.SINGLE)
                .userId(userId)
                .conversationId(conversationId)
                .character(character)
                .provider(request.getProvider())
                .model(request.getModel())
                .visionModel(request.getVisionModel())
                .temperature(request.getTemperature())
                .rawUserText(turn.rawLanguageSample())
                .modelUserText(turn.aiUserContent())
                .imageUrl(hasImage ? turn.userMsg().getImageUrl() : null)
                .currentUserMsgId(turn.userMsg().getId())
                .historyMessages(history)
                .streaming(false)
                .build());

        if (hasImage) {
            persistImageUserHistoryContent(
                    turn.userMsg().getId(), request.getContent(), chatResult.getImageDescription());
        }

        List<MessageResponse> replies = saveAssistantReplies(
                conversationId, character, chatResult.getContent(), chatResult.getTotalTokens());
        relationshipStateService.recordAssistantTurn(userId, character.getId(), conversationId, replies);

        memoryWriter.enqueueSummary(conversationId, character.getId(), userId,
                request.getProvider(), request.getModel());
        sessionSummaryService.maybeMergeAsync(conversationId, request.getProvider(), request.getModel());
        if (!replies.isEmpty()) {
            notificationService.notifyAssistantMessage(
                    userId,
                    conversationId,
                    character.getId(),
                    character.getName(),
                    replies.get(0).getContent(),
                    "MESSAGE"
            );
        }

        return replies.isEmpty() ? null : replies.get(0);
    }

    public SseEmitter sendMessageStream(Long userId, Long conversationId, SendMessageRequest request) {
        requireUserTextProvider(request);
        Conversation conversation = findOwned(userId, conversationId);
        Character character = characterMapper.selectById(conversation.getCharacterId());
        if (character == null) {
            throw new BusinessException(ErrorCode.CHARACTER_NOT_FOUND);
        }
        ensureCharacterNotBlocked(character);

        long userSeq = getNextSeq(conversationId);
        PreparedUserTurn turn = prepareUserTurn(conversationId, character.getId(), request, userSeq);
        messageMapper.insert(turn.userMsg());
        proactiveUnrepliedThrottle.resetOnUserReply(conversationId);
        List<Message> history = getRecentMessages(conversationId, contextWindow);
        relationshipStateService.recordUserTurn(
                userId,
                character.getId(),
                conversationId,
                turn.userMsg(),
                history);

        // 更新角色情绪
        characterStateService.afterUserMessage(character.getId(), userId, turn.aiUserContent());

        boolean hasImage = turn.userMsg().getImageUrl() != null && !turn.userMsg().getImageUrl().isBlank();
        final String userCaption = request.getContent();
        final Long userMessageId = turn.userMsg().getId();

        final Character streamCharacter = character;
        final CharacterChatBehavior streamBehavior = chatBehaviorResolver.resolve(character);
        AiChatService.StreamCallback callback = new AiChatService.StreamCallback() {
            @Override
            public void onVisionComplete(String imageDescription) {
                if (hasImage) {
                    persistImageUserHistoryContent(userMessageId, userCaption, imageDescription);
                }
            }

            @Override
            public void beforeStreamComplete(SseEmitter emitter, String fullContent) throws IOException {
                if (fullContent == null || fullContent.isBlank()) {
                    return;
                }
                AssistantReplyService.ProcessedReply processed = assistantReplyService.process(
                        fullContent, streamBehavior.maxRepliesPerTurn());
                Map<String, Object> payload = new LinkedHashMap<>();
                if (!processed.normalized().equals(fullContent)) {
                    payload.put("replace", processed.normalized());
                }
                if (!processed.pieces().isEmpty()) {
                    payload.put("pieces", processed.pieces());
                }
                if (!payload.isEmpty()) {
                    emitter.send(SseEmitter.event().data(objectMapper.writeValueAsString(payload)));
                }
            }

            @Override
            public void onComplete(String fullContent, Throwable error) {
                if (error != null && !isClientStreamDisconnect(error)) {
                    log.warn("Assistant stream failed, skip persist: convId={}, reason={}",
                            conversationId, error.getMessage());
                    return;
                }
                if (fullContent != null && !fullContent.isBlank()) {
                    if (error != null) {
                        log.info("Assistant stream client disconnected after generation, persist reply: convId={}, reason={}",
                                conversationId, error.getMessage());
                    }
                    List<MessageResponse> replies = saveAssistantReplies(
                            conversationId, streamCharacter, fullContent, null);
                    relationshipStateService.recordAssistantTurn(userId, character.getId(), conversationId, replies);
                    log.info("Assistant message saved: convId={}, pieces={}, size={} chars",
                            conversationId, replies.size(), fullContent.length());
                    memoryWriter.enqueueSummary(conversationId, character.getId(), userId,
                            request.getProvider(), request.getModel());
                    sessionSummaryService.maybeMergeAsync(conversationId, request.getProvider(), request.getModel());
                    if (!replies.isEmpty()) {
                        notificationService.notifyAssistantMessage(
                                userId,
                                conversationId,
                                character.getId(),
                                character.getName(),
                                replies.get(0).getContent(),
                                "MESSAGE"
                        );
                    }
                }
            }
        };
        return chatTurnFacade.invokeStream(ChatTurnCommand.builder()
                .scene(ChatTurnScene.SINGLE)
                .userId(userId)
                .conversationId(conversationId)
                .character(character)
                .provider(request.getProvider())
                .model(request.getModel())
                .visionModel(request.getVisionModel())
                .temperature(request.getTemperature())
                .rawUserText(turn.rawLanguageSample())
                .modelUserText(turn.aiUserContent())
                .imageUrl(hasImage ? turn.userMsg().getImageUrl() : null)
                .currentUserMsgId(turn.userMsg().getId())
                .historyMessages(history)
                .streaming(true)
                .build(), callback);
    }

    private boolean isClientStreamDisconnect(Throwable error) {
        return error instanceof java.io.IOException;
    }

    public MessagePageResponse getMessages(Long userId, Long conversationId, Long beforeSeq, int limit) {
        findOwned(userId, conversationId);
        int safeLimit = Math.min(200, Math.max(1, limit));
        LambdaQueryWrapper<Message> q = new LambdaQueryWrapper<Message>()
                .eq(Message::getConversationId, conversationId);
        if (beforeSeq != null && beforeSeq > 0) {
            q.lt(Message::getSeq, beforeSeq);
        }
        // safeLimit already clamped 1..200 at line 444; explicit int-to-string conversion
        q.orderByDesc(Message::getSeq).last("LIMIT " + Integer.valueOf(safeLimit + 1));
        List<Message> fetched = messageMapper.selectList(q);
        boolean hasMore = fetched.size() > safeLimit;
        if (hasMore) {
            fetched = fetched.subList(0, safeLimit);
        }
        java.util.Collections.reverse(fetched);
        Long nextBefore = null;
        if (hasMore && !fetched.isEmpty()) {
            nextBefore = fetched.get(0).getSeq();
        }
        List<MessageResponse> records = fetched.stream().map(this::toMessageResponse).toList();
        return MessagePageResponse.builder()
                .records(records)
                .hasMore(hasMore)
                .nextBeforeSeq(nextBefore)
                .build();
    }

    List<Message> getRecentMessages(Long conversationId, int limit) {
        List<Message> messages = messageMapper.selectList(new LambdaQueryWrapper<Message>()
                .eq(Message::getConversationId, conversationId)
                .orderByDesc(Message::getSeq)
                .last("LIMIT " + limit));
        // Return in chronological order (oldest first)
        java.util.Collections.reverse(messages);
        return messages;
    }

    Message findLastMessage(Long conversationId) {
        return messageMapper.selectList(new LambdaQueryWrapper<Message>()
                        .eq(Message::getConversationId, conversationId)
                        .orderByDesc(Message::getSeq)
                        .last("LIMIT 1"))
                .stream()
                .findFirst()
                .orElse(null);
    }

    long getNextSeq(Long conversationId) {
        return reserveSeqBlock(conversationId, 1);
    }

    private long reserveSeqBlock(Long conversationId, int count) {
        int safeCount = Math.max(1, count);
        Long lastSeq = redisTemplate.opsForValue().increment(SEQ_KEY_PREFIX + conversationId, safeCount);
        return lastSeq != null ? lastSeq : safeCount;
    }

    private Map<Long, String> buildLastMessageSnippetMap(List<Long> conversationIds) {
        if (conversationIds == null || conversationIds.isEmpty()) {
            return Map.of();
        }
        List<Message> lastMessages = messageMapper.selectLatestByConversationIds(conversationIds);
        return snippetMapFromMessages(lastMessages);
    }

    private Map<Long, String> buildLastCharacterMessageSnippetMap(List<Long> conversationIds) {
        if (conversationIds == null || conversationIds.isEmpty()) {
            return Map.of();
        }
        List<Message> lastMessages = messageMapper.selectLatestAssistantByConversationIds(conversationIds);
        return snippetMapFromMessages(lastMessages);
    }

    private Map<Long, String> snippetMapFromMessages(List<Message> messages) {
        Map<Long, String> result = new HashMap<>();
        for (Message lastMsg : messages) {
            if (lastMsg == null || lastMsg.getConversationId() == null) {
                continue;
            }
            String snippet = formatConversationPreviewSnippet(lastMsg);
            if (snippet != null && !snippet.isBlank()) {
                result.put(lastMsg.getConversationId(), snippet);
            }
        }
        return result;
    }

    /**
     * Bond-card / conversation list preview. Voice messages show duration instead of transcript.
     */
    static String formatConversationPreviewSnippet(Message message) {
        if (message == null) {
            return null;
        }
        String audioUrl = message.getAudioUrl();
        if (audioUrl != null && !audioUrl.isBlank()) {
            String trimmed = audioUrl.trim();
            if ("system/voice-call-summary".equals(trimmed)) {
                String content = message.getContent();
                return content == null || content.isBlank() ? "语音通话" : content;
            }
            if ("system/voice-call-turn".equals(trimmed)) {
                return "语音通话";
            }
            int seconds = estimateVoicePreviewSeconds(message.getContent());
            return "语音 " + seconds + "″";
        }
        String content = message.getContent();
        if (content == null || content.isBlank()) {
            return null;
        }
        return content.length() > 120 ? content.substring(0, 120) + "..." : content;
    }

    /** Rough conversational TTS pace (~3.2 Chinese chars/sec), matching frontend voice bubble. */
    static int estimateVoicePreviewSeconds(String text) {
        if (text == null || text.isBlank()) {
            return 1;
        }
        int chars = text.replaceAll("\\s+", "").length();
        if (chars <= 0) {
            return 1;
        }
        return Math.max(1, (int) Math.round(chars / 3.2));
    }

    Conversation findOwned(Long userId, Long conversationId) {
        Conversation conversation = conversationMapper.selectById(conversationId);
        if (conversation == null || !conversation.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.CONVERSATION_NOT_FOUND);
        }
        return conversation;
    }

    private void ensureCharacterNotBlocked(Character character) {
        if (isBlocked(character)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "该角色已被拉黑，无法发送消息");
        }
    }

    /** 用户可见聊天必须指定非 platform 的自有文本模型。 */
    private void requireUserTextProvider(SendMessageRequest request) {
        if (request == null || ApiKeyVaultService.isPlatformOrBlank(request.getProvider())) {
            throw new BusinessException(ErrorCode.AI_PROVIDER_ERROR,
                    "未配置文本模型，请在设置中添加");
        }
    }

    /**
     * 主动/冷启动等无显式 provider 时解析用户自有模型；无配置返回 null（调用方应静默跳过）。
     */
    VaultEntryResponse resolveUserTextVaultOrNull(Long userId) {
        return apiKeyVaultService.resolvePreferredUserVault(userId);
    }

    void ensureCharacterAvailableForProactive(Character character) {
        if (isBlocked(character)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "该角色已被拉黑");
        }
        if (CharacterPreferenceResolver.isDoNotDisturbActive(character)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "该角色当前处于免打扰时段");
        }
    }

    boolean isBlocked(Character character) {
        Map<String, Object> settings = character.getSettings();
        Object raw = settings == null ? null : settings.get("blocked");
        return raw instanceof Boolean b ? b : raw instanceof String s && Boolean.parseBoolean(s);
    }

    MessageDto buildSystemMessage(String content) {
        MessageDto dto = new MessageDto();
        dto.setRole("system");
        dto.setContent(content);
        return dto;
    }

    MessageDto buildUserMessage(String content) {
        MessageDto dto = new MessageDto();
        dto.setRole("user");
        dto.setContent(content);
        return dto;
    }

    private record PreparedUserTurn(Message userMsg, String aiUserContent, String rawLanguageSample) {}

    private PreparedUserTurn prepareUserTurn(Long conversationId,
                                             Long characterId,
                                             SendMessageRequest request,
                                             long seq) {
        String imageUrl = normalizeImageUrl(request.getImageUrl());
        UserInputSanitizer.SanitizedUserText sanitized = UserInputSanitizer.sanitizeChatMessage(request.getContent());
        String text = sanitized.storedText();
        boolean hasImage = imageUrl != null;
        // 纯图片：勿把空 <user_message/> 送给模型，否则文本阶段会当「空消息」忽略识图结果
        if (hasImage && text.isBlank()) {
            request.setModelContentForAi(UserInputSanitizer.wrapStoredTextForModel("用户发送了一张图片"));
        } else {
            request.setModelContentForAi(sanitized.modelText());
        }

        Message userMsg = new Message();
        userMsg.setSeq(seq);
        userMsg.setConversationId(conversationId);
        userMsg.setRole("USER");
        userMsg.setCharacterId(characterId);
        userMsg.setContent(resolveStoredUserContent(text, hasImage));
        userMsg.setImageUrl(imageUrl);

        String aiUserContent = request.getModelContentForAi();

        String rawLanguageSample = hasImage && text.isBlank() ? "用户发送了一张图片" : text;
        return new PreparedUserTurn(userMsg, aiUserContent, rawLanguageSample);
    }

    private String normalizeImageUrl(String imageUrl) {
        if (imageUrl == null || imageUrl.isBlank()) {
            return null;
        }
        String resolved = fileStorageService.resolvePublicUrl(imageUrl.trim());
        if (FileStorageService.extractObjectKey(resolved) == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "无效的图片地址");
        }
        return resolved;
    }

    private String resolveStoredUserContent(String text, boolean hasImage) {
        if (!text.isBlank()) {
            return text;
        }
        return hasImage ? ImageMessageHistoryText.placeholder(null) : "";
    }

    private void persistImageUserHistoryContent(Long messageId, String userCaption, String imageDescription) {
        if (messageId == null) {
            return;
        }
        // content = 用户可见文案（仅用户原话/通用占位）；contextContent = 模型历史（含识图描述）
        Message patch = new Message();
        patch.setId(messageId);
        patch.setContent(ImageMessageHistoryText.forUserVisible(userCaption));
        patch.setContextContent(ImageMessageHistoryText.forPersist(userCaption, imageDescription));
        messageMapper.updateById(patch);
    }

    private List<MessageResponse> saveAssistantReplies(Long conversationId,
                                                       Character character,
                                                       String fullContent,
                                                       Integer tokens) {
        CharacterChatBehavior behavior = chatBehaviorResolver.resolve(character);
        return saveAssistantRepliesLimited(conversationId, character, fullContent, tokens,
                behavior.maxRepliesPerTurn());
    }

    List<MessageResponse> saveAssistantRepliesLimited(Long conversationId,
                                                              Character character,
                                                              String fullContent,
                                                              Integer tokens,
                                                              int maxPiecesForSplit) {
        CharacterChatBehavior behavior = chatBehaviorResolver.resolve(character);
        int capped = Math.max(1, Math.min(Math.max(1, maxPiecesForSplit), behavior.maxRepliesPerTurn()));
        AssistantReplyService.ProcessedReply processed = assistantReplyService.process(fullContent, capped);
        List<String> pieces = processed.pieces();
        if (pieces.isEmpty()) {
            return List.of();
        }
        List<MessageResponse> saved = new ArrayList<>();
        Long characterId = character != null ? character.getId() : null;
        boolean showInnerThoughts = CharacterPreferenceResolver.showInnerThoughts(character);
        List<String> cleanedPieces = new ArrayList<>();
        for (String piece : pieces) {
            String cleaned = sanitizeAssistantText(piece);
            cleaned = InnerThoughtFilter.stripIfDisabled(cleaned, showInnerThoughts);
            if (!cleaned.isBlank()) {
                cleanedPieces.add(cleaned);
            }
        }
        if (cleanedPieces.isEmpty()) {
            return List.of();
        }
        long lastSeq = reserveSeqBlock(conversationId, cleanedPieces.size());
        long firstSeq = lastSeq - cleanedPieces.size() + 1;
        for (int i = 0; i < cleanedPieces.size(); i++) {
            Message assistantMsg = new Message();
            assistantMsg.setSeq(firstSeq + i);
            assistantMsg.setConversationId(conversationId);
            assistantMsg.setRole("ASSISTANT");
            assistantMsg.setCharacterId(characterId);
            assistantMsg.setContent(cleanedPieces.get(i));
            assistantMsg.setTokens(i == 0 ? tokens : null);
            messageMapper.insert(assistantMsg);
            saved.add(toMessageResponse(assistantMsg));
        }
        return saved;
    }

    private static final Pattern MULTI_SPACE = Pattern.compile("\\s{2,}");

    private String sanitizeAssistantText(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return MULTI_SPACE.matcher(text).replaceAll(" ").trim();
    }

    private ConversationResponse toResponse(Conversation conv, Character character) {
        return toResponse(conv, character, null, null);
    }

    private ConversationResponse toResponse(Conversation conv, Character character, String lastMessage) {
        return toResponse(conv, character, lastMessage, null);
    }

    private ConversationResponse toResponse(Conversation conv, Character character, String lastMessage,
                                            String lastCharacterMessage) {
        String characterName;
        if ("GROUP".equalsIgnoreCase(conv.getMode())) {
            characterName = "群聊";
        } else {
            characterName = character != null ? character.getName() : "(已删除)";
        }
        String characterAvatarUrl = character != null
                ? fileStorageService.resolvePublicUrl(character.getAvatarUrl())
                : null;
        String characterAvatarThumbUrl = character != null
                ? fileStorageService.resolveSquareAvatarThumbPublicUrl(character.getAvatarUrl())
                : null;
        return ConversationResponse.builder()
                .id(conv.getId())
                .userId(conv.getUserId())
                .characterId(conv.getCharacterId())
                .characterName(characterName)
                .characterAvatarUrl(characterAvatarUrl)
                .characterAvatarThumbUrl(characterAvatarThumbUrl)
                .mode(conv.getMode())
                .title(conv.getTitle())
                .lastMessage(lastMessage)
                .lastCharacterMessage(lastCharacterMessage)
                .createdAt(conv.getCreatedAt())
                .build();
    }

    MessageResponse toMessageResponse(Message msg) {
        String content = msg.getContent();
        // 带图用户消息：API 不回传内部识图占位，避免气泡里泄漏「（用户发了一张图片（…））」
        if (msg.getImageUrl() != null && !msg.getImageUrl().isBlank()
                && msg.getRole() != null && "USER".equalsIgnoreCase(msg.getRole())) {
            content = ImageMessageHistoryText.forDisplay(content);
        }
        return MessageResponse.builder()
                .id(msg.getId())
                .seq(msg.getSeq())
                .conversationId(msg.getConversationId())
                .role(msg.getRole())
                .characterId(msg.getCharacterId())
                .content(content)
                .imageUrl(fileStorageService.resolvePublicUrl(msg.getImageUrl()))
                .audioUrl(resolveMessageAudioUrl(msg.getAudioUrl()))
                .tokens(msg.getTokens())
                .createdAt(msg.getCreatedAt())
                .build();
    }

    /** Client-bundled pet clips stay as relative paths; remote URLs go through storage resolver. */
    private String resolveMessageAudioUrl(String audioUrl) {
        if (audioUrl == null || audioUrl.isBlank()) {
            return null;
        }
        String trimmed = audioUrl.trim();
        if (PetMeetVoiceCatalog.isSafeClientAudioPath(trimmed)) {
            return trimmed;
        }
        if ("system/voice-call-summary".equals(trimmed) || "system/voice-call-turn".equals(trimmed)) {
            return trimmed;
        }
        return fileStorageService.resolvePublicUrl(trimmed);
    }
}
