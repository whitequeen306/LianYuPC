package com.lianyu.service.conversation;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lianyu.common.base.ErrorCode;
import com.lianyu.common.constant.AiConstants;
import com.lianyu.common.exception.BusinessException;
import com.lianyu.common.i18n.OutputLanguage;
import com.lianyu.common.util.UserInputSanitizer;
import com.lianyu.dao.entity.Character;
import com.lianyu.dao.entity.Conversation;
import com.lianyu.dao.entity.GroupMember;
import com.lianyu.dao.entity.Message;
import com.lianyu.dao.mapper.CharacterMapper;
import com.lianyu.dao.mapper.ConversationMapper;
import com.lianyu.dao.mapper.GroupMemberMapper;
import com.lianyu.dao.mapper.MessageMapper;
import com.lianyu.service.ai.AiChatService;
import com.lianyu.service.ai.AssistantReplySplitter;
import com.lianyu.service.ai.CharacterPromptBuilder;
import com.lianyu.service.character.CharacterChatBehavior;
import com.lianyu.service.character.CharacterChatBehaviorResolver;
import com.lianyu.service.character.CharacterStateService;
import com.lianyu.service.dto.*;
import com.lianyu.service.memory.MemoryRetriever;
import com.lianyu.service.memory.MemoryWriter;
import com.lianyu.service.notification.NotificationService;
import com.lianyu.service.storage.FileStorageService;
import com.lianyu.service.support.OutputLanguageService;
import com.lianyu.service.tools.ChatToolContext;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
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
    private final CharacterPromptBuilder promptBuilder;
    private final MemoryRetriever memoryRetriever;
    private final MemoryWriter memoryWriter;
    private final StringRedisTemplate redisTemplate;
    private final FileStorageService fileStorageService;
    private final CharacterChatBehaviorResolver chatBehaviorResolver;
    private final AssistantReplySplitter replySplitter;
    private final NotificationService notificationService;
    private final OutputLanguageService outputLanguageService;
    private final CharacterStateService characterStateService;
    private final ProactiveRealWorldContextService proactiveRealWorldContext;

    @Lazy
    @Autowired
    private SingleChatOpeningScheduler singleChatOpeningScheduler;

    @Value("${lianyu.ai.context-window:20}")
    private int contextWindow;

    private static final Pattern STAGE_DIRECTION_PARENS =
            Pattern.compile("[（(][^（）()\\n]{1,60}[)）]");

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
        conversationMapper.insert(conversation);

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
                .orderByDesc(Conversation::getCreatedAt));

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
        return toResponse(conversation, character);
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
        log.info("Conversation deleted: id={}, mode={}", conversationId, conversation.getMode());
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

        // 更新角色情绪
        characterStateService.afterUserMessage(character.getId(), userId, turn.aiUserContent());

        String memoryContext = memoryRetriever.retrieveProfileContext(character.getId(), userId);
        String systemPrompt = buildSystemPromptForUser(userId, character, memoryContext, turn.memoryQuery());
        List<Message> history = getRecentMessages(conversationId, contextWindow);

        AiChatRequest aiRequest = buildChatRequest(
                request, character, systemPrompt, history, turn.userMsg().getId(), turn.aiUserContent());

        ChatResult chatResult = aiChatService.chatBlocking(userId, aiRequest);

        List<MessageResponse> replies = saveAssistantReplies(
                conversationId, character, chatResult.getContent(), chatResult.getTotalTokens());

        memoryWriter.enqueueSummary(conversationId, character.getId(), userId);
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

        // 更新角色情绪
        characterStateService.afterUserMessage(character.getId(), userId, turn.aiUserContent());

        String memoryContext = memoryRetriever.retrieveProfileContext(character.getId(), userId);
        String systemPrompt = buildSystemPromptForUser(userId, character, memoryContext, turn.memoryQuery());
        List<Message> history = getRecentMessages(conversationId, contextWindow);

        AiChatRequest aiRequest = buildChatRequest(
                request, character, systemPrompt, history, turn.userMsg().getId(), turn.aiUserContent());

        final Character streamCharacter = character;
        return aiChatService.chatStream(userId, aiRequest, (fullContent, error) -> {
            if (fullContent != null && !fullContent.isBlank()) {
                List<MessageResponse> replies = saveAssistantReplies(
                        conversationId, streamCharacter, fullContent, null);
                log.info("Assistant message saved: convId={}, pieces={}, size={} chars",
                        conversationId, replies.size(), fullContent.length());
                memoryWriter.enqueueSummary(conversationId, character.getId(), userId);
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
        });
    }

    @Transactional
    public List<MessageResponse> sendProactiveMessage(Long userId, Long conversationId, String hint) {
        Conversation conversation = findOwned(userId, conversationId);
        Character character = characterMapper.selectById(conversation.getCharacterId());
        if (character == null) {
            throw new BusinessException(ErrorCode.CHARACTER_NOT_FOUND);
        }
        ensureCharacterAvailableForProactive(character);

        String memoryContext = memoryRetriever.retrieveProfileContext(character.getId(), userId);
        String systemPrompt = proactiveSystemPrompt(userId, character, memoryContext, null);
        List<Message> history = getRecentMessages(conversationId, contextWindow);

        AiChatRequest aiRequest = new AiChatRequest();
        ChatToolContext.bindTo(aiRequest, character);
        List<MessageDto> allMessages = new ArrayList<>();
        allMessages.add(buildSystemMessage(systemPrompt));
        for (Message msg : history) {
            MessageDto dto = new MessageDto();
            dto.setRole(msg.getRole().toLowerCase());
            dto.setContent(msg.getContent());
            allMessages.add(dto);
        }
        CharacterChatBehavior behavior = chatBehaviorResolver.resolve(character);
        int maxPieces = behavior.maxRepliesPerTurn();
        allMessages.add(buildUserMessage(String.format("""
                你现在要主动给用户发一段消息，而不是等待用户先开口。
                要求：
                1) 1~%d条短消息（符合你的性格，话多的可多条，话少的可一条）；
                2) 如果是多条，请用空行分隔；
                3) 语气自然，围绕最近上下文或角色设定关心用户；
                4) 不要重复历史原话，不要机械问候。
                """, maxPieces)));
        aiRequest.setMessages(allMessages);

        ChatResult chatResult = aiChatService.chatBlocking(userId, aiRequest);
        List<MessageResponse> replies = saveAssistantReplies(
                conversationId, character, chatResult.getContent(), chatResult.getTotalTokens());
        if (!replies.isEmpty()) {
            memoryWriter.enqueueSummary(conversationId, character.getId(), userId);
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
        try {
            ensureCharacterAvailableForProactive(character);
        } catch (BusinessException e) {
            log.debug("Cold open skipped (character unavailable): convId={}, reason={}", conversationId, e.getMessage());
            return;
        }
        Message tail = findLastMessage(conversationId);
        if (tail != null) {
            return;
        }

        CharacterChatBehavior behavior = chatBehaviorResolver.resolve(character);
        int maxPieces = behavior.maxRepliesPerTurn();
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
        List<MessageResponse> replies = saveAssistantRepliesLimited(
                conversationId, character, chatResult.getContent(), chatResult.getTotalTokens(), maxPieces);
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
        log.info("Cold open first line: convId={}, pieces={}", conversationId, replies.size());
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
        return redisTemplate.opsForValue().increment(SEQ_KEY_PREFIX + conversationId);
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
            if (lastMsg == null || lastMsg.getConversationId() == null || lastMsg.getContent() == null) {
                continue;
            }
            String content = lastMsg.getContent();
            result.put(lastMsg.getConversationId(), content.length() > 120 ? content.substring(0, 120) + "..." : content);
        }
        return result;
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
        if (isDoNotDisturbActive(character)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "该角色当前处于免打扰时段");
        }
    }

    private boolean isBlocked(Character character) {
        Map<String, Object> settings = character.getSettings();
        Object raw = settings == null ? null : settings.get("blocked");
        return raw instanceof Boolean b ? b : raw instanceof String s && Boolean.parseBoolean(s);
    }

    private boolean isDoNotDisturbActive(Character character) {
        Map<String, Object> settings = character.getSettings();
        if (settings == null) {
            return false;
        }
        if (!booleanSetting(settings, "doNotDisturbEnabled", false)) {
            return false;
        }
        int start = intSetting(settings, "dndStartMinutes", 23 * 60);
        int end = intSetting(settings, "dndEndMinutes", 8 * 60);
        int now = LocalTime.now().getHour() * 60 + LocalTime.now().getMinute();
        if (start == end) {
            return true;
        }
        if (start < end) {
            return now >= start && now < end;
        }
        return now >= start || now < end;
    }

    private boolean booleanSetting(Map<String, Object> settings, String key, boolean fallback) {
        Object raw = settings.get(key);
        if (raw instanceof Boolean b) {
            return b;
        }
        if (raw instanceof String s) {
            return Boolean.parseBoolean(s);
        }
        return fallback;
    }

    private int intSetting(Map<String, Object> settings, String key, int fallback) {
        Object raw = settings.get(key);
        if (raw instanceof Number n) {
            return n.intValue();
        }
        if (raw instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return fallback;
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

    private record PreparedUserTurn(Message userMsg, String memoryQuery, String aiUserContent) {}

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
        if (hasImage) {
            String description = aiChatService.describeImage(imageUrl);
            String plainAi = buildAiUserContent(text, description);
            aiUserContent = UserInputSanitizer.wrapStoredTextForModel(plainAi);
            request.setModelContentForAi(aiUserContent);
        }

        String memoryQuery = hasImage && text.isBlank() ? "用户发送了一张图片" : aiUserContent;
        return new PreparedUserTurn(userMsg, memoryQuery, aiUserContent);
    }

    private AiChatRequest buildChatRequest(SendMessageRequest request,
                                           Character character,
                                           String systemPrompt,
                                           List<Message> history,
                                           Long currentUserMsgId,
                                           String currentAiUserContent) {
        AiChatRequest aiRequest = new AiChatRequest();
        aiRequest.setProvider(request.getProvider());
        aiRequest.setModel(request.getModel());
        aiRequest.setTemperature(request.getTemperature());
        ChatToolContext.bindTo(aiRequest, character);
        aiRequest.setMessages(buildChatMessageDtos(
                systemPrompt, history, currentUserMsgId, currentAiUserContent));
        return aiRequest;
    }

    private String buildAiUserContent(String text, String imageDescription) {
        StringBuilder sb = new StringBuilder();
        if (text != null && !text.isBlank()) {
            sb.append(text.trim());
        }
        if (imageDescription != null && !imageDescription.isBlank()) {
            if (!sb.isEmpty()) {
                sb.append("\n\n");
            }
            sb.append("[用户发送了一张图片，识图结果：").append(imageDescription.trim()).append(']');
        }
        return sb.toString();
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

    /**
     * 构建发给主聊天模型的消息列表。有图时 currentAiUserContent 已含识图结果，不再传图片。
     */
    private List<MessageDto> buildChatMessageDtos(String systemPrompt,
                                                  List<Message> history,
                                                  Long currentUserMsgId,
                                                  String currentAiUserContent) {
        List<MessageDto> allMessages = new ArrayList<>();
        allMessages.add(buildSystemMessage(systemPrompt));
        for (Message msg : history) {
            MessageDto dto = new MessageDto();
            dto.setRole(msg.getRole().toLowerCase());
            String content = msg.getContent();
            if (currentUserMsgId != null
                    && currentUserMsgId.equals(msg.getId())
                    && currentAiUserContent != null
                    && !currentAiUserContent.isBlank()) {
                content = currentAiUserContent.contains("<user_message")
                        ? currentAiUserContent
                        : UserInputSanitizer.wrapStoredTextForModel(currentAiUserContent);
            } else if (content == null || content.isBlank()) {
                if (msg.getImageUrl() != null && !msg.getImageUrl().isBlank()) {
                    content = "（用户发送了一张图片）";
                } else {
                    continue;
                }
            }
            dto.setContent(content);
            allMessages.add(dto);
        }
        return allMessages;
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
        List<String> pieces = replySplitter.split(fullContent, capped);
        if (pieces.isEmpty()) {
            return List.of();
        }
        List<MessageResponse> saved = new ArrayList<>();
        Long characterId = character != null ? character.getId() : null;
        for (int i = 0; i < pieces.size(); i++) {
            String cleaned = sanitizeAssistantText(pieces.get(i));
            if (cleaned.isBlank()) {
                continue;
            }
            Message assistantMsg = new Message();
            assistantMsg.setSeq(getNextSeq(conversationId));
            assistantMsg.setConversationId(conversationId);
            assistantMsg.setRole("ASSISTANT");
            assistantMsg.setCharacterId(characterId);
            assistantMsg.setContent(cleaned);
            assistantMsg.setTokens(i == 0 ? tokens : null);
            messageMapper.insert(assistantMsg);
            saved.add(toMessageResponse(assistantMsg));
        }
        return saved;
    }

    private String sanitizeAssistantText(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        // 去掉“（微笑）/（沉默片刻）”这类舞台说明，保留自然口语文本
        String result = STAGE_DIRECTION_PARENS.matcher(text).replaceAll("");
        result = result.replaceAll("\\s{2,}", " ").trim();
        if (result.isBlank()) {
            result = text.replace("（", "").replace("）", "")
                    .replace("(", "").replace(")", "").trim();
        }
        return result;
    }

    private String buildSystemPromptForUser(Long userId, Character character, String memoryContext, String userInput) {
        String lang = outputLanguageService.resolveForRequest(userId, userInput);
        String base = promptBuilder.buildSystemPrompt(character, memoryContext, lang, true);
        base = appendGoodnightContextIfApplicable(base, userInput, lang);
        return enforceNaturalChatStyle(base, lang);
    }

    /** 单聊主动开口（含破冰/跟进）：在 system 中预置已查询的真实时间与天气。 */
    private String proactiveSystemPrompt(Long userId, Character character, String memoryContext, String userInput) {
        return buildSystemPromptForUser(userId, character, memoryContext, userInput)
                + proactiveRealWorldContext.buildBlock(character);
    }

    /**
     * 晚安仪式：23:00-08:00 + 晚安关键词 → 注入特殊晚安指令。
     */
    private String appendGoodnightContextIfApplicable(String basePrompt, String userInput, String lang) {
        if (userInput == null || userInput.isBlank()) {
            return basePrompt;
        }
        if (!isGoodnightTime() || !containsGoodnightKeyword(userInput)) {
            return basePrompt;
        }
        return switch (OutputLanguage.fromCode(lang)) {
            case JA -> basePrompt + """

                    === おやすみモード（最優先） ===
                    ユーザーが「おやすみ」を言った。あなたはとても優しく、落ち着いた口調で応答する。
                    静かに相手を眠りに誘い、短く温かいおやすみの言葉をかける。長文は避け、1〜3文で。
                    子守唄や寝物語を求められたら短く応じてもよい。""";
            case EN -> basePrompt + """

                    === Goodnight Mode (Highest Priority) ===
                    The user just said goodnight. Respond with your gentlest, most soothing voice.
                    Help them drift off to sleep with a short, warm goodnight message. Keep it to 1-3 sentences.
                    If they ask for a lullaby or bedtime story, you may provide a short one.""";
            case ZH_TW -> basePrompt + """

                    === 晚安模式（最高優先） ===
                    用戶剛剛說了晚安。用你最溫柔、最平靜的語氣回應。
                    輕輕哄對方入睡，送上一句溫暖的晚安。控制在1-3句話，不要長篇大論。
                    如果對方想聽睡前故事或搖籃曲，可以簡短回應。""";
            default -> basePrompt + """

                    === 晚安模式（最高优先级） ===
                    用户刚刚说了晚安。用你最温柔、最平静的语气回应。
                    轻轻哄对方入睡，送上一句温暖的晚安。控制在1-3句话，不要长篇大论。
                    如果对方想听睡前故事或摇篮曲，可以简短回应。""";
        };
    }

    private boolean isGoodnightTime() {
        int minutes = LocalTime.now().getHour() * 60 + LocalTime.now().getMinute();
        return minutes >= 23 * 60 || minutes < 8 * 60; // 23:00 - 08:00
    }

    private static final Pattern GOODNIGHT_KEYWORDS = Pattern.compile(
            "晚安|睡了|睡觉|眠い|おやすみ|good\\s*night|nighty|night night|gn|安|困了|我要睡了|先睡了|去睡了|睡吧");

    private boolean containsGoodnightKeyword(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        return GOODNIGHT_KEYWORDS.matcher(text.toLowerCase()).find();
    }

    private String enforceNaturalChatStyle(String basePrompt, String languageCode) {
        String prompt = basePrompt == null ? "" : basePrompt;
        return prompt + outputLanguageService.buildNaturalStyleBlock(languageCode);
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
        return ConversationResponse.builder()
                .id(conv.getId())
                .userId(conv.getUserId())
                .characterId(conv.getCharacterId())
                .characterName(characterName)
                .characterAvatarUrl(characterAvatarUrl)
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
                .tokens(msg.getTokens())
                .createdAt(msg.getCreatedAt())
                .build();
    }
}
