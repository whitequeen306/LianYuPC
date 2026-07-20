package com.lianyu.service.conversation;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lianyu.common.base.ErrorCode;
import com.lianyu.common.constant.AiConstants;
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
import com.lianyu.service.ai.AssistantReplyService;
import com.lianyu.service.ai.CharacterPromptBuilder;
import com.lianyu.service.ai.PetMeetVoiceCatalog;
import com.lianyu.service.ai.PetVoiceRegistry;
import com.lianyu.service.graph.ChatTurnCommand;
import com.lianyu.service.graph.ChatTurnFacade;
import com.lianyu.service.graph.ChatTurnResult;
import com.lianyu.service.character.CharacterChatBehavior;
import com.lianyu.service.character.CharacterChatBehaviorResolver;
import com.lianyu.service.ai.InnerThoughtFilter;
import com.lianyu.service.character.CharacterPreferenceResolver;
import com.lianyu.service.character.CharacterRecentActivityService;
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
    private static final String COLD_OPEN_LOCK_PREFIX = "coldopen:lock:";
    private static final String FIXED_VOICE_ENTER_PREFIX = "chat:fixed-voice:enter:";
    private static final String FIXED_VOICE_SLOT_PREFIX = "chat:fixed-voice:slot:";
    private static final java.time.ZoneId FIXED_VOICE_ZONE = java.time.ZoneId.of("Asia/Shanghai");
    /** Re-enter chat voice at most once per this TTL. */
    private static final java.time.Duration ENTER_VOICE_COOLDOWN = java.time.Duration.ofHours(6);
    /** Skip enter voice if last activity was more recent than this. */
    private static final long ENTER_MIN_IDLE_MINUTES = 20;

    private final ConversationMapper conversationMapper;
    private final MessageMapper messageMapper;
    private final GroupMemberMapper groupMemberMapper;
    private final CharacterMapper characterMapper;
    private final CharacterSquareTemplateMapper squareTemplateMapper;
    private final UserMapper userMapper;
    private final AiChatService aiChatService;
    private final PetMeetVoiceCatalog petMeetVoiceCatalog;
    private final PetVoiceRegistry petVoiceRegistry;
    private final ChatTurnFacade chatTurnFacade;
    private final CharacterPromptBuilder promptBuilder;
    private final MemoryRetriever memoryRetriever;
    private final MemoryWriter memoryWriter;
    private final StringRedisTemplate redisTemplate;
    private final FileStorageService fileStorageService;
    private final CharacterChatBehaviorResolver chatBehaviorResolver;
    private final AssistantReplyService assistantReplyService;
    private final ObjectMapper objectMapper;
    private final NotificationService notificationService;
    private final OutputLanguageService outputLanguageService;
    private final CharacterStateService characterStateService;
    private final ProactiveRealWorldContextService proactiveRealWorldContext;
    private final RelationshipStateService relationshipStateService;
    private final ProactiveUnrepliedThrottle proactiveUnrepliedThrottle;
    private final TimeTool timeTool;
    private final SessionSummaryService sessionSummaryService;
    private final CharacterRecentActivityService characterRecentActivityService;

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
                .temperature(request.getTemperature())
                .rawUserText(turn.rawLanguageSample())
                .modelUserText(turn.aiUserContent())
                .imageUrl(hasImage ? turn.userMsg().getImageUrl() : null)
                .currentUserMsgId(turn.userMsg().getId())
                .historyMessages(history)
                .streaming(false)
                .build());

        List<MessageResponse> replies = saveAssistantReplies(
                conversationId, character, chatResult.getContent(), chatResult.getTotalTokens());
        relationshipStateService.recordAssistantTurn(userId, character.getId(), conversationId, replies);

        memoryWriter.enqueueSummary(conversationId, character.getId(), userId);
        sessionSummaryService.maybeMergeAsync(conversationId);
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

        final Character streamCharacter = character;
        final CharacterChatBehavior streamBehavior = chatBehaviorResolver.resolve(character);
        AiChatService.StreamCallback callback = new AiChatService.StreamCallback() {
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
                    memoryWriter.enqueueSummary(conversationId, character.getId(), userId);
                    sessionSummaryService.maybeMergeAsync(conversationId);
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

    @Transactional
    public List<MessageResponse> sendProactiveMessage(Long userId, Long conversationId, String hint) {
        Conversation conversation = findOwned(userId, conversationId);
        Character character = characterMapper.selectById(conversation.getCharacterId());
        if (character == null) {
            throw new BusinessException(ErrorCode.CHARACTER_NOT_FOUND);
        }
        ensureCharacterAvailableForProactive(character);

        List<Message> history = getRecentMessages(conversationId, contextWindow);
        CharacterChatBehavior behavior = chatBehaviorResolver.resolve(character);
        int maxPieces = behavior.maxRepliesPerTurn();
        List<MessageDto> prepared = new ArrayList<>();
        prepared.add(buildSystemMessage(""));
        for (Message msg : history) {
            MessageDto dto = new MessageDto();
            dto.setRole(msg.getRole().toLowerCase());
            dto.setContent(msg.getContent());
            prepared.add(dto);
        }
        prepared.add(buildUserMessage(String.format("""
                你现在要主动给用户发一段消息，而不是等待用户先开口。
                要求：
                1) 1~%d条短消息（符合你的性格，话多的可多条，话少的可一条）；
                2) 如果是多条，请用空行分隔；
                3) 语气自然，围绕最近上下文或角色设定关心用户；
                4) 不要重复历史原话，不要机械问候。
                """, maxPieces)));

        ChatTurnResult chatResult = chatTurnFacade.invokeBlocking(ChatTurnCommand.builder()
                .scene(ChatTurnScene.PROACTIVE)
                .userId(userId)
                .conversationId(conversationId)
                .character(character)
                .rawUserText(null)
                .modelUserText(null)
                .preparedMessages(prepared)
                .historyMessages(history)
                .streaming(false)
                .build());
        List<MessageResponse> replies = saveAssistantReplies(
                conversationId, character, chatResult.getContent(), chatResult.getTotalTokens());
        if (!replies.isEmpty()) {
            proactiveUnrepliedThrottle.recordProactiveSent(conversationId);
            memoryWriter.enqueueSummary(conversationId, character.getId(), userId);
            notificationService.notifyProactiveMessage(
                    userId,
                    conversationId,
                    character.getId(),
                    character.getName(),
                    replies.get(0).getContent());
        }
        return replies;
    }

    /**
     * 新建单聊后第一波：角色先发破冰话（会话仍为空时才会写入，避免并发重复）。
     */
    @Transactional
    public void sendColdOpenFirstLine(Long userId, Long conversationId) {
        Conversation conversation = findOwned(userId, conversationId);
        if (!"SINGLE".equalsIgnoreCase(conversation.getMode())) {
            return;
        }
        Character character = characterMapper.selectById(conversation.getCharacterId());
        if (character == null) {
            return;
        }
        CharacterChatBehavior coldOpenBehavior = chatBehaviorResolver.resolve(character);
        if (!coldOpenBehavior.proactiveEnabled()) {
            log.debug("Cold open skipped (proactive disabled): convId={}", conversationId);
            return;
        }
        try {
            ensureCharacterAvailableForProactive(character);
        } catch (BusinessException e) {
            log.debug("Cold open skipped (character unavailable): convId={}, reason={}", conversationId, e.getMessage());
            return;
        }
        String lockKey = COLD_OPEN_LOCK_PREFIX + conversationId;
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(lockKey, "1",
                java.time.Duration.ofMinutes(2));
        if (Boolean.FALSE.equals(acquired)) {
            log.debug("Cold open skipped (lock held): convId={}", conversationId);
            return;
        }
        try {
            Message tail = findLastMessage(conversationId);
            if (tail != null) {
                return;
            }

            // VC square pets: always use the fixed meet clip (no AI / time / weather).
            if (resolveVcPetId(character) != null) {
                if (trySendFixedMeetVoice(userId, conversationId, character)) {
                    return;
                }
                log.warn("Fixed meet voice missing for VC characterId={}, falling back to text cold open",
                        character.getId());
            }

            CharacterChatBehavior behavior = chatBehaviorResolver.resolve(character);
            // Cold-open text: 1–2 short lines (not the full chat burst budget).
            int maxPieces = Math.min(2, Math.max(1, behavior.maxRepliesPerTurn()));
            String memoryContext = memoryRetriever.retrieveProfileContext(character.getId(), userId);
            String systemPrompt = proactiveSystemPrompt(userId, character, memoryContext, null);

            AiChatRequest aiRequest = new AiChatRequest();
            ChatToolContext.bindTo(aiRequest, character);
            aiRequest.setProvider(AiConstants.PLATFORM_PROVIDER);
            List<MessageDto> allMessages = new ArrayList<>();
            allMessages.add(buildSystemMessage(systemPrompt));
            allMessages.add(buildUserMessage(String.format("""
                            这是你和该用户在本会话里的第一次开口（会话中还没有任何历史消息）。
                            请你主动先发 1～%d 条很短的破冰话，符合你的口吻与设定，自然一点，避免机械客服腔。
                            若多条请用空行分隔；不要堆砌长段落。
                            """, maxPieces)));
            aiRequest.setMessages(allMessages);

            ChatResult chatResult = aiChatService.chatBlocking(userId, aiRequest);
            if (findLastMessage(conversationId) != null) {
                return;
            }
            List<MessageResponse> replies = saveAssistantRepliesLimited(
                    conversationId, character, chatResult.getContent(), chatResult.getTotalTokens(), maxPieces);
            if (!replies.isEmpty()) {
                memoryWriter.enqueueSummary(conversationId, character.getId(), userId);
                notificationService.notifyProactiveMessage(
                        userId,
                        conversationId,
                        character.getId(),
                        character.getName(),
                        replies.get(0).getContent());
                log.info("Cold open first line: convId={}, pieces={}", conversationId, replies.size());
            }
        } finally {
            redisTemplate.delete(lockKey);
        }
    }

    private String resolveVcPetId(Character character) {
        if (character == null || character.getSourceTemplateId() == null) {
            return null;
        }
        CharacterSquareTemplate template = squareTemplateMapper.selectById(character.getSourceTemplateId());
        if (template == null || template.getSlug() == null) {
            return null;
        }
        String slug = template.getSlug().trim().toLowerCase();
        return petVoiceRegistry.hasVoice(slug) ? slug : null;
    }

    /**
     * User opened a SINGLE chat page. VC square pets may send a short fixed "welcome back" voice
     * (enter), alternating with normal text/AI turns elsewhere. Cooldown + idle gate avoid spam.
     */
    @Transactional
    public List<MessageResponse> onSingleChatOpened(Long userId, Long conversationId) {
        Conversation conversation = findOwned(userId, conversationId);
        if (!"SINGLE".equalsIgnoreCase(conversation.getMode())) {
            return List.of();
        }
        Character character = characterMapper.selectById(conversation.getCharacterId());
        if (character == null) {
            return List.of();
        }
        try {
            ensureCharacterAvailableForProactive(character);
        } catch (BusinessException e) {
            return List.of();
        }
        Message last = findLastMessage(conversationId);
        if (last == null) {
            // First open: cold-open / meet voice owns the empty session.
            return List.of();
        }
        if (last.getCreatedAt() != null
                && last.getCreatedAt().isAfter(java.time.LocalDateTime.now().minusMinutes(ENTER_MIN_IDLE_MINUTES))) {
            return List.of();
        }
        String enterKey = FIXED_VOICE_ENTER_PREFIX + conversationId;
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(enterKey, "1", ENTER_VOICE_COOLDOWN);
        if (!Boolean.TRUE.equals(acquired)) {
            return List.of();
        }
        MessageResponse sent = insertFixedVoiceIfPresent(
                userId, conversationId, character, PetMeetVoiceCatalog.Kind.ENTER);
        if (sent == null) {
            redisTemplate.delete(enterKey);
            return List.of();
        }
        return List.of(sent);
    }

    /**
     * After two unreplied AI proactives: one character-flavored fixed "why no reply" voice,
     * then pause further proactives until the user replies.
     */
    @Transactional
    public List<MessageResponse> trySendWaitNudgeVoice(Long userId, Long conversationId) {
        Conversation conversation = findOwned(userId, conversationId);
        if (!"SINGLE".equalsIgnoreCase(conversation.getMode())) {
            return List.of();
        }
        if (!proactiveUnrepliedThrottle.shouldSendWaitVoice(conversationId)) {
            return List.of();
        }
        Character character = characterMapper.selectById(conversation.getCharacterId());
        if (character == null) {
            return List.of();
        }
        try {
            ensureCharacterAvailableForProactive(character);
        } catch (BusinessException e) {
            return List.of();
        }
        MessageResponse sent = insertFixedVoiceIfPresent(
                userId, conversationId, character, PetMeetVoiceCatalog.Kind.WAIT);
        if (sent == null) {
            return List.of();
        }
        proactiveUnrepliedThrottle.markWaitVoiceSent(conversationId);
        return List.of(sent);
    }

    /**
     * Noon / evening fixed voice for VC square pets. Returns a reply list when sent;
     * caller should apply proactive cooldown. Daily per-slot Redis key prevents repeats.
     */
    @Transactional
    public List<MessageResponse> trySendTimedFixedVoice(Long userId, Long conversationId) {
        Conversation conversation = findOwned(userId, conversationId);
        if (!"SINGLE".equalsIgnoreCase(conversation.getMode())) {
            return List.of();
        }
        Character character = characterMapper.selectById(conversation.getCharacterId());
        if (character == null) {
            return List.of();
        }
        try {
            ensureCharacterAvailableForProactive(character);
        } catch (BusinessException e) {
            return List.of();
        }
        if (proactiveUnrepliedThrottle.isPaused(conversationId)) {
            return List.of();
        }
        if (findLastMessage(conversationId) == null) {
            return List.of();
        }
        PetMeetVoiceCatalog.Kind slot = resolveTimedVoiceSlot(java.time.LocalTime.now(FIXED_VOICE_ZONE));
        if (slot == null) {
            return List.of();
        }
        String day = java.time.LocalDate.now(FIXED_VOICE_ZONE).toString();
        String slotKey = FIXED_VOICE_SLOT_PREFIX + slot.fileStem() + ":" + conversationId + ":" + day;
        Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(slotKey, "1", java.time.Duration.ofHours(26));
        if (!Boolean.TRUE.equals(acquired)) {
            return List.of();
        }
        MessageResponse sent = insertFixedVoiceIfPresent(userId, conversationId, character, slot);
        if (sent == null) {
            redisTemplate.delete(slotKey);
            return List.of();
        }
        return List.of(sent);
    }

    /** Asia/Shanghai: noon 11:30–13:30, evening 18:30–21:00. */
    public static PetMeetVoiceCatalog.Kind resolveTimedVoiceSlot(java.time.LocalTime localTime) {
        if (localTime == null) {
            return null;
        }
        java.time.LocalTime noonStart = java.time.LocalTime.of(11, 30);
        java.time.LocalTime noonEnd = java.time.LocalTime.of(13, 30);
        java.time.LocalTime eveningStart = java.time.LocalTime.of(18, 30);
        java.time.LocalTime eveningEnd = java.time.LocalTime.of(21, 0);
        if (!localTime.isBefore(noonStart) && localTime.isBefore(noonEnd)) {
            return PetMeetVoiceCatalog.Kind.NOON;
        }
        if (!localTime.isBefore(eveningStart) && localTime.isBefore(eveningEnd)) {
            return PetMeetVoiceCatalog.Kind.EVENING;
        }
        return null;
    }

    /**
     * Square characters with a desktop-pet VC mapping get a fixed first-meet voice message
     * instead of an AI-generated cold-open line.
     */
    private boolean trySendFixedMeetVoice(Long userId, Long conversationId, Character character) {
        if (findLastMessage(conversationId) != null) {
            return true;
        }
        return insertFixedVoiceIfPresent(userId, conversationId, character, PetMeetVoiceCatalog.Kind.MEET)
                != null;
    }

    private MessageResponse insertFixedVoiceIfPresent(Long userId,
                                                      Long conversationId,
                                                      Character character,
                                                      PetMeetVoiceCatalog.Kind kind) {
        if (character.getSourceTemplateId() == null) {
            return null;
        }
        CharacterSquareTemplate template = squareTemplateMapper.selectById(character.getSourceTemplateId());
        if (template == null || template.getSlug() == null) {
            return null;
        }
        PetMeetVoiceCatalog.MeetClip clip = petMeetVoiceCatalog.find(template.getSlug(), kind);
        if (clip == null || !PetMeetVoiceCatalog.isSafeClientAudioPath(clip.audioPath())) {
            return null;
        }
        long seq = getNextSeq(conversationId);
        Message assistantMsg = new Message();
        assistantMsg.setSeq(seq);
        assistantMsg.setConversationId(conversationId);
        assistantMsg.setRole("ASSISTANT");
        assistantMsg.setCharacterId(character.getId());
        assistantMsg.setContent(clip.text());
        assistantMsg.setAudioUrl(clip.audioPath());
        messageMapper.insert(assistantMsg);
        notificationService.notifyProactiveMessage(
                userId,
                conversationId,
                character.getId(),
                character.getName(),
                clip.text());
        log.info("Fixed chat voice: convId={}, kind={}, petId={}, slug={}",
                conversationId, kind.fileStem(), clip.petId(), template.getSlug());
        return toMessageResponse(assistantMsg);
    }

    /**
     * 若用户在「首条破冰」之后仍未回复（最后一条仍为助手侧），则再补一条简短关心；仅此一次，不会再发第三条。
     */
    @Transactional
    public void sendColdOpenFollowUpIfStillSilent(Long userId, Long conversationId) {
        Conversation conversation = findOwned(userId, conversationId);
        if (!"SINGLE".equalsIgnoreCase(conversation.getMode())) {
            return;
        }
        Character character = characterMapper.selectById(conversation.getCharacterId());
        if (character == null) {
            return;
        }
        CharacterChatBehavior followUpBehavior = chatBehaviorResolver.resolve(character);
        if (!followUpBehavior.proactiveEnabled()) {
            log.debug("Cold open follow-up skipped (proactive disabled): convId={}", conversationId);
            return;
        }
        try {
            ensureCharacterAvailableForProactive(character);
        } catch (BusinessException e) {
            return;
        }

        Message last = findLastMessage(conversationId);
        if (last == null) {
            return;
        }
        if ("USER".equalsIgnoreCase(last.getRole())) {
            return;
        }

        String memoryContext = memoryRetriever.retrieveProfileContext(character.getId(), userId);
        String systemPrompt = proactiveSystemPrompt(userId, character, memoryContext, null);
        List<Message> history = getRecentMessages(conversationId, contextWindow);

        AiChatRequest aiRequest = new AiChatRequest();
        ChatToolContext.bindTo(aiRequest, character);
        aiRequest.setProvider(AiConstants.PLATFORM_PROVIDER);
        List<MessageDto> allMessages = new ArrayList<>();
        allMessages.add(buildSystemMessage(systemPrompt));
        for (Message msg : history) {
            MessageDto dto = new MessageDto();
            dto.setRole(msg.getRole().toLowerCase());
            dto.setContent(msg.getContent());
            allMessages.add(dto);
        }
        allMessages.add(buildUserMessage("""
                用户在你上一条之后仍未回复。请再发**唯一一条**非常简短的关心或轻轻一推（一两句话即可）。
                不要重复上一句的意思，不要过于啰嗦。
                这是本条场景下系统允许你的**最后一次自动开口**；发完后安静等用户就好。"""));

        aiRequest.setMessages(allMessages);
        ChatResult chatResult = aiChatService.chatBlocking(userId, aiRequest);
        List<MessageResponse> replies = saveAssistantRepliesLimited(
                conversationId, character, chatResult.getContent(), chatResult.getTotalTokens(), 1);
        if (!replies.isEmpty()) {
            memoryWriter.enqueueSummary(conversationId, character.getId(), userId);
            notificationService.notifyProactiveMessage(
                    userId,
                    conversationId,
                    character.getId(),
                    character.getName(),
                    replies.get(0).getContent()
            );
        }
        log.info("Cold open follow-up: convId={}, pieces={}", conversationId, replies.size());
    }

    /**
     * 用户修改现实城市后，由最近有消息的单聊角色主动关心是否搬家。
     */
    @Transactional
    public void sendCityChangeFollowUp(Long userId, String previousCity, String newCity) {
        Optional<Conversation> recentOpt = findMostRecentSingleConversation(userId);
        if (recentOpt.isEmpty()) {
            log.debug("City change follow-up skipped: no single conversation, userId={}", userId);
            return;
        }
        Conversation conversation = recentOpt.get();
        Long conversationId = conversation.getId();
        Character character = characterMapper.selectById(conversation.getCharacterId());
        if (character == null) {
            return;
        }
        CharacterChatBehavior behavior = chatBehaviorResolver.resolve(character);
        if (!behavior.proactiveEnabled()) {
            log.debug("City change follow-up skipped: proactive disabled, convId={}", conversationId);
            return;
        }
        try {
            ensureCharacterAvailableForProactive(character);
        } catch (BusinessException e) {
            log.debug("City change follow-up skipped: convId={}, reason={}", conversationId, e.getMessage());
            return;
        }

        String memoryContext = memoryRetriever.retrieveProfileContext(character.getId(), userId);
        String relationshipContext = relationshipStateService.buildPromptContext(userId, character.getId());
        String mergedMemory = memoryContext == null ? relationshipContext
                : memoryContext + "\n\n" + relationshipContext;
        String addressing = resolveUserAddressing(userId, memoryContext);

        String systemPrompt = proactiveSystemPrompt(userId, character, mergedMemory, null);
        systemPrompt = appendCityChangeContext(systemPrompt, previousCity, newCity);

        List<Message> history = getRecentMessages(conversationId, contextWindow);
        AiChatRequest aiRequest = new AiChatRequest();
        ChatToolContext.bindTo(aiRequest, character);
        aiRequest.setProvider(AiConstants.PLATFORM_PROVIDER);
        List<MessageDto> allMessages = new ArrayList<>();
        allMessages.add(buildSystemMessage(systemPrompt));
        for (Message msg : history) {
            MessageDto dto = new MessageDto();
            dto.setRole(msg.getRole().toLowerCase());
            dto.setContent(msg.getContent());
            allMessages.add(dto);
        }
        allMessages.add(buildUserMessage(String.format("""
                系统检测到用户刚刚把自己的现实所在城市从「%s」改成了「%s」。
                请你主动发一条关心用户的消息，核心要问用户是不是搬家/换城市了、发生什么事了。
                必须用称呼「%s」；必须明确提到从「%s」到「%s」的变化。
                参考句式（可略作口语化，但不要改城市名、不要否认搬迁）：%s，我看你从%s来到了%s，是发生了什么事情吗？
                只发 1 条，不要太长；不要重复历史原话。
                """, previousCity, newCity, addressing, previousCity, newCity,
                addressing, previousCity, newCity)));
        aiRequest.setMessages(allMessages);

        ChatResult chatResult = aiChatService.chatBlocking(userId, aiRequest);
        List<MessageResponse> replies = saveAssistantRepliesLimited(
                conversationId, character, chatResult.getContent(), chatResult.getTotalTokens(), 1);
        if (!replies.isEmpty()) {
            memoryWriter.enqueueSummary(conversationId, character.getId(), userId);
            notificationService.notifyProactiveMessage(
                    userId,
                    conversationId,
                    character.getId(),
                    character.getName(),
                    replies.get(0).getContent()
            );
        }
        log.info("City change follow-up: convId={}, {} -> {}", conversationId, previousCity, newCity);
    }

    private Optional<Conversation> findMostRecentSingleConversation(Long userId) {
        List<Conversation> singles = conversationMapper.selectList(new LambdaQueryWrapper<Conversation>()
                .eq(Conversation::getUserId, userId)
                .eq(Conversation::getMode, "SINGLE")
                .isNotNull(Conversation::getCharacterId));
        if (singles.isEmpty()) {
            return Optional.empty();
        }
        List<Long> convIds = singles.stream().map(Conversation::getId).toList();
        List<Message> latestMessages = messageMapper.selectLatestByConversationIds(convIds);
        if (latestMessages.isEmpty()) {
            return Optional.empty();
        }
        Message newest = latestMessages.stream()
                .max(Comparator.comparing(Message::getCreatedAt))
                .orElse(null);
        if (newest == null) {
            return Optional.empty();
        }
        return singles.stream()
                .filter(c -> c.getId().equals(newest.getConversationId()))
                .findFirst();
    }

    private String resolveUserAddressing(Long userId, String memoryContext) {
        User user = userMapper.selectById(userId);
        String nickname = user != null ? user.getNickname() : null;
        return UserAddressingResolver.resolve(memoryContext, nickname);
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

    private List<Message> getRecentMessages(Long conversationId, int limit) {
        List<Message> messages = messageMapper.selectList(new LambdaQueryWrapper<Message>()
                .eq(Message::getConversationId, conversationId)
                .orderByDesc(Message::getSeq)
                .last("LIMIT " + limit));
        // Return in chronological order (oldest first)
        java.util.Collections.reverse(messages);
        return messages;
    }

    private Message findLastMessage(Long conversationId) {
        return messageMapper.selectList(new LambdaQueryWrapper<Message>()
                        .eq(Message::getConversationId, conversationId)
                        .orderByDesc(Message::getSeq)
                        .last("LIMIT 1"))
                .stream()
                .findFirst()
                .orElse(null);
    }

    private long getNextSeq(Long conversationId) {
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

    private Conversation findOwned(Long userId, Long conversationId) {
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

    private void ensureCharacterAvailableForProactive(Character character) {
        if (isBlocked(character)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "该角色已被拉黑");
        }
        if (CharacterPreferenceResolver.isDoNotDisturbActive(character)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "该角色当前处于免打扰时段");
        }
    }

    private boolean isBlocked(Character character) {
        Map<String, Object> settings = character.getSettings();
        Object raw = settings == null ? null : settings.get("blocked");
        return raw instanceof Boolean b ? b : raw instanceof String s && Boolean.parseBoolean(s);
    }

    private MessageDto buildSystemMessage(String content) {
        MessageDto dto = new MessageDto();
        dto.setRole("system");
        dto.setContent(content);
        return dto;
    }

    private MessageDto buildUserMessage(String content) {
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
        request.setModelContentForAi(sanitized.modelText());
        boolean hasImage = imageUrl != null;

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
        return hasImage ? "（用户发送了一张图片）" : "";
    }

    private List<MessageResponse> saveAssistantReplies(Long conversationId,
                                                       Character character,
                                                       String fullContent,
                                                       Integer tokens) {
        CharacterChatBehavior behavior = chatBehaviorResolver.resolve(character);
        return saveAssistantRepliesLimited(conversationId, character, fullContent, tokens,
                behavior.maxRepliesPerTurn());
    }

    private List<MessageResponse> saveAssistantRepliesLimited(Long conversationId,
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

    private String appendCityChangeContext(String basePrompt, String previousCity, String newCity) {
        return basePrompt + """


                === 用户城市变更（最高优先级，权威事实） ===
                """ + "用户当前现实所在城市：" + newCity + "\n"
                + "（刚才从 " + previousCity + " 变更而来。）\n\n"
                + "对话历史里若仍写用户还在 " + previousCity + " 或基于旧城市的天气/地点，一律视为过时信息，必须以本条为准。\n"
                + "你本次主动开口就是为了关心用户这次换城市，不要假装用户还在旧城市。";
    }

    /** 单聊主动开口（含破冰/跟进）：走 PROACTIVE scene（含真实时间/天气块）。 */
    private String proactiveSystemPrompt(Long userId, Character character, String memoryContext, String userInput) {
        return chatTurnFacade.assembleSystemPrompt(
                ChatTurnScene.PROACTIVE,
                userId,
                null,
                character,
                userInput,
                userInput,
                null,
                null);
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

    private MessageResponse toMessageResponse(Message msg) {
        return MessageResponse.builder()
                .id(msg.getId())
                .seq(msg.getSeq())
                .conversationId(msg.getConversationId())
                .role(msg.getRole())
                .characterId(msg.getCharacterId())
                .content(msg.getContent())
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
        return fileStorageService.resolvePublicUrl(trimmed);
    }
}
