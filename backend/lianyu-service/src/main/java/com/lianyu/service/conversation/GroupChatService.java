package com.lianyu.service.conversation;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lianyu.common.base.ErrorCode;
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
import com.lianyu.service.agent.SceneInstructionPort;
import com.lianyu.service.ai.ApiKeyVaultService;
import com.lianyu.service.ai.AssistantReplyService;
import com.lianyu.service.ai.AssistantReplySplitter;
import com.lianyu.service.ai.InnerThoughtFilter;
import com.lianyu.service.character.CharacterChatBehavior;
import com.lianyu.service.character.CharacterChatBehaviorResolver;
import com.lianyu.service.character.CharacterPreferenceResolver;
import com.lianyu.service.character.CharacterService;
import com.lianyu.service.dto.*;
import com.lianyu.service.notification.NotificationService;
import com.lianyu.service.storage.FileStorageService;
import com.lianyu.service.support.OutputLanguageService;
import com.lianyu.service.tools.ChatToolContext;
import jakarta.annotation.Resource;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskExecutor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class GroupChatService {

    private final ConversationMapper conversationMapper;
    private final GroupMemberMapper groupMemberMapper;
    private final CharacterMapper characterMapper;
    private final MessageMapper messageMapper;
    private final com.lianyu.service.agent.AgentBlockingPort agentBlockingPort;
    private final ApiKeyVaultService apiKeyVaultService;
    private final com.lianyu.service.graph.ChatTurnFacade chatTurnFacade;
    private final OutputLanguageService outputLanguageService;
    private final SimpMessagingTemplate messagingTemplate;
    private final StringRedisTemplate redisTemplate;
    private final FileStorageService fileStorageService;
    private final CharacterChatBehaviorResolver chatBehaviorResolver;
    private final CharacterService characterService;
    private final AssistantReplyService assistantReplyService;
    private final AssistantReplySplitter replySplitter;
    private final NotificationService notificationService;
    private final ObjectMapper objectMapper;
    @Resource(name = "groupChatExecutor")
    private TaskExecutor taskExecutor;

    /**
     * 群聊 prompt 文案由 Python 提供（批次 B）。端口缺失/渲染失败时跳过本次主动发言，
     * 绝不在 Java 留兜底文案 —— 四语任务块原来就长在这个类里（约 24 行 switch）。
     */
    @Autowired(required = false)
    private SceneInstructionPort promptPort;

    @Value("${lianyu.ai.context-window:20}")
    private int contextWindow;
    @Value("${lianyu.group.max-members:4}")
    private int maxGroupMembers;
    @Value("${lianyu.group.auto-rounds:2}")
    private int maxAutoRounds;
    @Value("${lianyu.group.bubble-gap-ms:500}")
    private long bubbleGapMs;
    @Value("${lianyu.group.mention-judge.enabled:true}")
    private boolean mentionJudgeEnabled;
    @Value("${lianyu.group.mention-judge.threshold:0.8}")
    private double mentionJudgeThreshold;
    @Value("${lianyu.group.mention-judge.max-context-messages:6}")
    private int mentionJudgeContextMessages;

    private static final String SEQ_KEY_PREFIX = "msg_seq:";
    private static final String TURN_KEY_PREFIX = "group_chat:turn:";
    private static final Duration TURN_TTL = Duration.ofMinutes(5);
    private static final CompletableFuture<?>[] EMPTY_CF_ARRAY = new CompletableFuture<?>[0];

    private static final String IMPLICIT_TRIGGER_WORDS = "(觉得呢|怎么看|你呢|怎么看呢|什么看法|说说看|回答下|回复下)";
    private static final String IMPLICIT_PUNCTUATION = "[，。！？!?,\\s]*";
    private static final String IMPLICIT_ASK_PREFIX = "(问问|请问|问下|让)";
    private static final String IMPLICIT_SENTENCE_END = "(吧|呀|吗)[？?]?";

    private final ConcurrentHashMap<String, Pattern[]> implicitMentionPatterns = new ConcurrentHashMap<>();

    @Transactional
    public ConversationResponse createGroup(Long userId, CreateGroupConversationRequest request) {
        List<Long> characterIds = request.getCharacterIds();
        if (characterIds != null && characterIds.size() > maxGroupMembers) {
            throw new BusinessException(ErrorCode.GROUP_FULL,
                    "群聊最多 " + maxGroupMembers + " 个角色");
        }

        Conversation conversation = new Conversation();
        conversation.setUserId(userId);
        conversation.setMode("GROUP");
        conversation.setTitle(request.getTitle());
        conversationMapper.insert(conversation);

        List<Character> characters = characterMapper.selectBatchIds(characterIds);
        Map<Long, Character> charMap = characters.stream()
                .collect(Collectors.toMap(Character::getId, c -> c, (a, b) -> a));
        int order = 0;
        for (Long characterId : characterIds) {
            Character character = charMap.get(characterId);
            if (character == null || !character.getOwnerUserId().equals(userId)) {
                throw new BusinessException(ErrorCode.CHARACTER_NOT_FOUND,
                        "角色不存在或不属于你: " + characterId);
            }
            GroupMember member = new GroupMember();
            member.setConversationId(conversation.getId());
            member.setCharacterId(characterId);
            member.setSortOrder(order++);
            groupMemberMapper.insert(member);
        }

        log.info("Group conversation created: id={}, members={}", conversation.getId(), characterIds);
        return ConversationResponse.builder()
                .id(conversation.getId())
                .userId(conversation.getUserId())
                .mode("GROUP")
                .title(conversation.getTitle())
                .createdAt(conversation.getCreatedAt())
                .build();
    }

    @Transactional
    public ConversationResponse updateTitle(Long userId, Long conversationId, UpdateGroupTitleRequest request) {
        Conversation conversation = findOwned(userId, conversationId);
        if (!"GROUP".equalsIgnoreCase(conversation.getMode())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "该会话不是群聊");
        }
        String title = normalizeGroupTitle(request != null ? request.getTitle() : null);
        conversation.setTitle(title);
        conversationMapper.updateById(conversation);
        log.info("Group title updated: id={}, title={}", conversationId, title);
        return ConversationResponse.builder()
                .id(conversation.getId())
                .userId(conversation.getUserId())
                .mode(conversation.getMode())
                .title(conversation.getTitle())
                .createdAt(conversation.getCreatedAt())
                .build();
    }

    private static String normalizeGroupTitle(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * 角色在群聊中主动发言（定时任务调用）；尽量以 @ 用户或其他角色开头。
     */
    public boolean sendProactiveGroupMessage(Long userId,
                                             Long conversationId,
                                             Long characterId,
                                             String contextHint) {
        VaultEntryResponse userVault = apiKeyVaultService.resolvePreferredUserVault(userId);
        if (userVault == null) {
            log.debug("Proactive group chat skipped: no user text model, userId={}, convId={}",
                    userId, conversationId);
            return false;
        }
        Conversation conversation = findOwned(userId, conversationId);
        if (!"GROUP".equalsIgnoreCase(conversation.getMode())) {
            return false;
        }

        List<GroupMember> members = groupMemberMapper.selectList(
                new LambdaQueryWrapper<GroupMember>()
                        .eq(GroupMember::getConversationId, conversationId)
                        .orderByAsc(GroupMember::getSortOrder));
        if (members.size() < 2) {
            return false;
        }

        Character character = characterMapper.selectById(characterId);
        if (character == null || !character.getOwnerUserId().equals(userId)) {
            return false;
        }
        if (!members.stream().anyMatch(m -> characterId.equals(m.getCharacterId()))) {
            return false;
        }

        List<Message> historySnapshot = getRecentMessages(conversationId, contextWindow);
        if (historySnapshot.isEmpty()) {
            return false;
        }

        Map<Long, String> nameMap = loadCharacterNameMap(members);
        String mentionTarget = pickProactiveMentionTarget(character, members, nameMap);
        String warmHint = (contextHint == null || contextHint.isBlank()) ? "最近群聊" : contextHint.trim();

        CharacterChatBehavior behavior = chatBehaviorResolver.resolve(character);
        int maxPieces = behavior.maxRepliesPerTurn();
        String mentionCtx = buildMentionContext(character, historySnapshot, nameMap);

        String outputLang = outputLanguageService.resolveForRequest(userId, warmHint);
        String othersLine = buildOtherCharactersContext(members, character, outputLang);

        List<MessageDto> allMsgs = new ArrayList<>();
        MessageDto sysDto = new MessageDto();
        sysDto.setRole("system");
        sysDto.setContent("");
        allMsgs.add(sysDto);

        for (Message msg : historySnapshot) {
            MessageDto dto = new MessageDto();
            dto.setRole(msg.getRole().toLowerCase());
            String content = msg.getContent();
            if (msg.getCharacterId() != null && !msg.getCharacterId().equals(character.getId())) {
                String charName = getCharacterName(msg.getCharacterId(), nameMap);
                content = formatOtherCharacterHistoryLine(charName, content);
            }
            dto.setContent(content);
            allMsgs.add(dto);
        }

        String proactiveSuffix = buildProactiveGroupSuffix(outputLang, mentionTarget, maxPieces, warmHint);
        if (proactiveSuffix == null) {
            log.warn("Proactive group chat skipped: task block unavailable, convId={}", conversationId);
            return false;
        }
        // Task text in system; trailing user turn is only a non-user trigger (same as single-chat proactive).
        allMsgs.add(buildUserMessage("（系统触发主动发言，用户未发送新消息。）"));

        try {
            var result = chatTurnFacade.invokeBlocking(com.lianyu.service.graph.ChatTurnCommand.builder()
                    .scene(com.lianyu.ai.graph.ChatTurnScene.GROUP)
                    .userId(userId)
                    .character(character)
                    .provider(userVault.getProvider())
                    .model(userVault.getModelDefault())
                    .temperature(0.85)
                    .rawUserText(warmHint)
                    .preparedMessages(allMsgs)
                    .extraSystemSuffix(proactiveSuffix)
                    .groupExtras(new com.lianyu.service.graph.ChatTurnGroupExtras(
                            maxPieces, othersLine, mentionCtx))
                    .streaming(false)
                    .build());
            String cleanedContent = sanitizeGroupReply(result.getContent(), character.getName(), members, nameMap);
            if (cleanedContent == null || cleanedContent.isBlank()) {
                return false;
            }
            CharacterReply reply = new CharacterReply(
                    character.getId(), character.getName(), cleanedContent, result.getTotalTokens());

            SendMessageRequest userReq = new SendMessageRequest();
            userReq.setProvider(userVault.getProvider());
            userReq.setModel(userVault.getModelDefault());
            persistProactiveGroupReplies(userId, conversationId, reply, members, mentionTarget, userReq,
                    character, nameMap);
            return true;
        } catch (Exception e) {
            log.debug("Proactive group chat skipped: convId={}, characterId={}, reason={}",
                    conversationId, characterId, e.getMessage());
            return false;
        }
    }

    public List<CharacterResponse> listMembers(Long userId, Long conversationId) {
        Conversation conversation = findOwned(userId, conversationId);
        if (!"GROUP".equalsIgnoreCase(conversation.getMode())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "该会话不是群聊");
        }
        List<GroupMember> members = groupMemberMapper.selectList(
                new LambdaQueryWrapper<GroupMember>()
                        .eq(GroupMember::getConversationId, conversationId)
                        .orderByAsc(GroupMember::getSortOrder));
        List<CharacterResponse> result = new ArrayList<>();
        List<Long> characterIds = members.stream().map(GroupMember::getCharacterId).toList();
        if (!characterIds.isEmpty()) {
            List<Character> characters = characterMapper.selectBatchIds(characterIds);
            Map<Long, Character> charMap = characters.stream()
                    .collect(Collectors.toMap(Character::getId, c -> c, (a, b) -> a));
            for (GroupMember member : members) {
                Character character = charMap.get(member.getCharacterId());
                if (character != null) {
                    // Same mapping as single-chat: square-template avatar + thumb URL.
                    result.add(characterService.toPublicResponse(character));
                }
            }
        }
        return result;
    }

    public void handleGroupMessage(Long userId, Long conversationId, SendMessageRequest request) {
        if (request == null || ApiKeyVaultService.isPlatformOrBlank(request.getProvider())) {
            throw new BusinessException(ErrorCode.AI_PROVIDER_ERROR,
                    "未配置文本模型，请在设置中添加");
        }
        Conversation conversation = findOwned(userId, conversationId);
        if (!"GROUP".equalsIgnoreCase(conversation.getMode())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "该会话不是群聊");
        }

        List<GroupMember> members = groupMemberMapper.selectList(
                new LambdaQueryWrapper<GroupMember>()
                        .eq(GroupMember::getConversationId, conversationId)
                        .orderByAsc(GroupMember::getSortOrder));

        if (members.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "群聊没有成员");
        }

        Map<Long, Character> characterMap = loadCharacterMap(members);
        Map<Long, String> memberNameMap = toNameMap(characterMap);

        long userSeq = getNextSeq(conversationId);

        Message userMsg = new Message();
        userMsg.setSeq(userSeq);
        userMsg.setConversationId(conversationId);
        userMsg.setRole("USER");
        UserInputSanitizer.SanitizedUserText sanitized = UserInputSanitizer.sanitizeChatMessage(
                request.getContent() != null ? request.getContent() : "");
        String normalizedUserContent = normalizeImplicitMentions(sanitized.storedText(), members, memberNameMap);
        request.setContent(normalizedUserContent);
        request.setModelContentForAi(UserInputSanitizer.wrapStoredTextForModel(normalizedUserContent));
        userMsg.setContent(normalizedUserContent);
        messageMapper.insert(userMsg);
        final Long currentUserMsgId = userMsg.getId();
        Set<Long> userMentionedIds = extractMentionedCharacterIds(normalizedUserContent, members, memberNameMap);

        // Set a new turn ID to interrupt any in-progress character replies
        String turnId = UUID.randomUUID().toString();
        String turnKey = TURN_KEY_PREFIX + conversationId;
        redisTemplate.opsForValue().set(turnKey, turnId, TURN_TTL);

        sendToGroup(conversationId, GroupMessageResponse.builder()
                .type("USER_MESSAGE")
                .conversationId(conversationId)
                .content(normalizedUserContent)
                .build());

        CompletableFuture.runAsync(() -> {
            boolean anyReplyDelivered = false;
            for (int round = 1; round <= maxAutoRounds; round++) {
                if (!turnId.equals(redisTemplate.opsForValue().get(turnKey))) {
                    log.info("Group chat turn interrupted: conversationId={}, round={}", conversationId, round);
                    sendToGroup(conversationId, GroupMessageResponse.builder()
                            .type("TURN_INTERRUPTED")
                            .conversationId(conversationId)
                            .content("已被新消息中断")
                            .build());
                    return;
                }

                List<Message> historySnapshot = getRecentMessages(conversationId, contextWindow);
                List<GroupMember> roundMembers = members;
                if (round == 1 && !userMentionedIds.isEmpty()) {
                    roundMembers = members.stream()
                            .filter(m -> userMentionedIds.contains(m.getCharacterId()))
                            .toList();
                    if (roundMembers.isEmpty()) {
                        roundMembers = members;
                    }
                }
                // Product note (CL-019): replies persist in LLM completion order, not roster sort_order.
                List<CompletableFuture<CharacterReply>> futures = new ArrayList<>();
                for (GroupMember member : roundMembers) {
                    futures.add(CompletableFuture.supplyAsync(
                            () -> generateCharacterReply(member, members, userId, request, historySnapshot,
                                    currentUserMsgId, characterMap, memberNameMap), taskExecutor));
                }

                List<CompletableFuture<CharacterReply>> pending = new ArrayList<>(futures);
                int repliedCount = 0;
                while (!pending.isEmpty()) {
                    CompletableFuture.anyOf(pending.toArray(EMPTY_CF_ARRAY)).join();
                    for (int i = pending.size() - 1; i >= 0; i--) {
                        CompletableFuture<CharacterReply> f = pending.get(i);
                        if (!f.isDone()) {
                            continue;
                        }
                        pending.remove(i);
                        CharacterReply reply = null;
                        try {
                            reply = f.join();
                        } catch (Exception ignored) {
                            // handled in generateCharacterReply
                        }
                        if (reply == null || reply.content() == null || reply.content().isBlank()) {
                            continue;
                        }
                        if (!turnId.equals(redisTemplate.opsForValue().get(turnKey))) {
                            sendToGroup(conversationId, GroupMessageResponse.builder()
                                    .type("TURN_INTERRUPTED")
                                    .conversationId(conversationId)
                                    .content("已被新消息中断")
                                    .build());
                            return;
                        }
                        persistAndBroadcastReplies(conversationId, reply, members, userId, request, historySnapshot,
                                turnId, turnKey, characterMap, memberNameMap);
                        repliedCount++;
                    }
                }

                if (repliedCount == 0) {
                    break;
                }
                anyReplyDelivered = true;
            }

            if (turnId.equals(redisTemplate.opsForValue().get(turnKey))) {
                if (!anyReplyDelivered) {
                    sendToGroup(conversationId, GroupMessageResponse.builder()
                            .type("TURN_ERROR")
                            .conversationId(conversationId)
                            .content("角色回复失败，请稍后再试")
                            .build());
                    return;
                }
                sendToGroup(conversationId, GroupMessageResponse.builder()
                        .type("TURN_COMPLETE")
                        .conversationId(conversationId)
                        .content("本轮回复结束")
                        .build());
            }
        }, taskExecutor);
    }

    private CharacterReply generateCharacterReply(
            GroupMember member,
            List<GroupMember> members,
            Long userId,
            SendMessageRequest request,
            List<Message> historySnapshot,
            Long currentUserMsgId,
            Map<Long, Character> characterMap,
            Map<Long, String> nameMap) {
        try {
            Character character = characterMap.get(member.getCharacterId());
            if (character == null) {
                return null;
            }

            CharacterChatBehavior behavior = chatBehaviorResolver.resolve(character);
            int maxPieces = behavior.maxRepliesPerTurn();
            String mentionCtx = buildMentionContext(character, historySnapshot, nameMap);
            String outputLang = outputLanguageService.resolveForRequest(userId, request.getContent());
            String othersLine = buildOtherCharactersContext(members, character, outputLang);

            List<MessageDto> allMsgs = new ArrayList<>();
            MessageDto sysDto = new MessageDto();
            sysDto.setRole("system");
            sysDto.setContent("");
            allMsgs.add(sysDto);

            for (Message msg : historySnapshot) {
                MessageDto dto = new MessageDto();
                dto.setRole(msg.getRole().toLowerCase());
                String content = msg.getContent();
                if (currentUserMsgId != null
                        && currentUserMsgId.equals(msg.getId())
                        && "USER".equalsIgnoreCase(msg.getRole())
                        && request.getModelContentForAi() != null) {
                    content = request.getModelContentForAi();
                } else if (content != null
                        && !content.isBlank()
                        && "USER".equalsIgnoreCase(msg.getRole())
                        && !content.contains("<user_message")) {
                    content = UserInputSanitizer.wrapStoredTextForModel(content);
                }
                if (msg.getCharacterId() != null && !msg.getCharacterId().equals(character.getId())) {
                    String charName = getCharacterName(msg.getCharacterId(), nameMap);
                    content = formatOtherCharacterHistoryLine(charName, content);
                }
                dto.setContent(content);
                allMsgs.add(dto);
            }

            var result = chatTurnFacade.invokeBlocking(com.lianyu.service.graph.ChatTurnCommand.builder()
                    .scene(com.lianyu.ai.graph.ChatTurnScene.GROUP)
                    .userId(userId)
                    .character(character)
                    .provider(request.getProvider())
                    .model(request.getModel())
                    .visionModel(request.getVisionModel())
                    .temperature(request.getTemperature())
                    .rawUserText(request.getContent())
                    .modelUserText(request.getModelContentForAi())
                    .preparedMessages(allMsgs)
                    .groupExtras(new com.lianyu.service.graph.ChatTurnGroupExtras(
                            maxPieces, othersLine, mentionCtx))
                    .streaming(false)
                    .build());
            String cleanedContent = sanitizeGroupReply(result.getContent(), character.getName(), members, nameMap);
            return new CharacterReply(character.getId(), character.getName(), cleanedContent, result.getTotalTokens());
        } catch (Exception e) {
            log.error("Group chat error for character {}: {}", member.getCharacterId(), e.getMessage());
            return null;
        }
    }

    private void persistProactiveGroupReplies(Long userId,
                                              Long conversationId,
                                              CharacterReply reply,
                                              List<GroupMember> members,
                                              String preferredMentionTarget,
                                              SendMessageRequest request,
                                              Character character,
                                              Map<Long, String> nameMap) {
        CharacterChatBehavior behavior = chatBehaviorResolver.resolve(character);
        List<String> pieces = assistantReplyService.process(
                reply.content(), behavior.maxRepliesPerTurn()).pieces();
        if (pieces.isEmpty()) {
            return;
        }

        List<String> ready = new ArrayList<>();
        for (String piece : pieces) {
            if (piece == null || piece.isBlank()) {
                continue;
            }
            String cleaned = sanitizeGroupReply(piece, reply.characterName(), members, nameMap);
            cleaned = filterInnerThoughtsForCharacter(character, cleaned);
            if (cleaned == null || cleaned.isBlank()) {
                continue;
            }
            cleaned = ensureProactiveMentionPrefix(cleaned, preferredMentionTarget, members, nameMap);
            ready.add(cleaned);
        }
        if (ready.isEmpty()) {
            return;
        }

        long lastSeq = reserveSeqBlock(conversationId, ready.size());
        long firstSeq = lastSeq - ready.size() + 1;
        for (int i = 0; i < ready.size(); i++) {
            String cleaned = ready.get(i);
            Message charMsg = new Message();
            charMsg.setSeq(firstSeq + i);
            charMsg.setConversationId(conversationId);
            charMsg.setRole("ASSISTANT");
            charMsg.setCharacterId(reply.characterId());
            charMsg.setContent(cleaned);
            charMsg.setTokens(i == 0 ? reply.tokens() : null);
            messageMapper.insert(charMsg);

            sendToGroup(conversationId, GroupMessageResponse.builder()
                    .type("CHARACTER_MESSAGE")
                    .conversationId(conversationId)
                    .characterId(reply.characterId())
                    .characterName(reply.characterName())
                    .content(cleaned)
                    .build());

            notifyGroupUnread(userId, conversationId, reply.characterId(), reply.characterName(), cleaned);

            if (i < ready.size() - 1 && bubbleGapMs > 0) {
                try {
                    Thread.sleep(bubbleGapMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    /**
     * 群聊主动发言的 system 任务块：段头 + 四语任务说明 + 最近气氛 + 禁止条款。
     *
     * <p>文案在 Python（{@code app/prompts/group_proactive_task_{zh,zh_tw,ja,en}.md}），
     * 前导空行 2 与旧拼接口径逐字节一致（拼接口径：文本块后跟 "最近气氛参考：" 与禁止条款，
     * 段头与禁止条款在任何语种下都保持中文）。取不到返回 null，调用方跳过本次生成。
     */
    private String buildProactiveGroupSuffix(String outputLang, String mentionTarget,
                                             int maxPieces, String warmHint) {
        if (promptPort == null) {
            log.warn("Proactive group task block unavailable: prompt port missing");
            return null;
        }
        String promptName = switch (OutputLanguage.fromCode(outputLang)) {
            case JA -> "group_proactive_task_ja";
            case EN -> "group_proactive_task_en";
            case ZH_TW -> "group_proactive_task_zh_tw";
            case ZH -> "group_proactive_task_zh";
        };
        return promptPort.render(promptName, Map.of(
                "mention_target", mentionTarget == null ? "" : mentionTarget,
                "max_pieces", maxPieces,
                "warm_hint", warmHint == null ? "" : warmHint), 2);
    }

    private String pickProactiveMentionTarget(Character speaker, List<GroupMember> members,
                                              Map<Long, String> nameMap) {
        List<String> targets = new ArrayList<>();
        targets.add("你");
        for (GroupMember member : members) {
            if (member.getCharacterId().equals(speaker.getId())) {
                continue;
            }
            String name = getCharacterName(member.getCharacterId(), nameMap);
            if (name != null && !name.isBlank()) {
                targets.add(name);
            }
        }
        return targets.get(ThreadLocalRandom.current().nextInt(targets.size()));
    }

    private String ensureProactiveMentionPrefix(String content,
                                                  String preferredTarget,
                                                  List<GroupMember> members,
                                                  Map<Long, String> nameMap) {
        if (content == null || content.isBlank()) {
            return content;
        }
        if (containsAnyMention(content, members, nameMap)) {
            return content;
        }
        String target = (preferredTarget == null || preferredTarget.isBlank()) ? "你" : preferredTarget;
        String trimmed = content.trim();
        if (trimmed.startsWith("@" + target)) {
            return trimmed;
        }
        return "@" + target + " " + trimmed;
    }

    private void persistAndBroadcastReplies(Long conversationId,
                                            CharacterReply reply,
                                            List<GroupMember> members,
                                            Long userId,
                                            SendMessageRequest request,
                                            List<Message> historySnapshot,
                                            String turnId,
                                            String turnKey,
                                            Map<Long, Character> characterMap,
                                            Map<Long, String> nameMap) {
        Character character = characterMap.get(reply.characterId());
        if (character == null) {
            return;
        }
        CharacterChatBehavior behavior = chatBehaviorResolver.resolve(character);
        List<String> pieces = assistantReplyService.process(
                reply.content(), behavior.maxRepliesPerTurn()).pieces();
        if (pieces.isEmpty()) {
            return;
        }

        List<String> ready = new ArrayList<>();
        for (String piece : pieces) {
            if (piece == null || piece.isBlank()) {
                continue;
            }
            String cleaned = sanitizeGroupReply(piece, reply.characterName(), members, nameMap);
            cleaned = filterInnerThoughtsForCharacter(character, cleaned);
            if (cleaned == null || cleaned.isBlank()) {
                continue;
            }
            cleaned = enhanceMentionsForAssistantReply(
                    cleaned, reply.characterName(), members, nameMap, userId, request, historySnapshot);
            ready.add(cleaned);
        }
        if (ready.isEmpty()) {
            return;
        }

        long lastSeq = reserveSeqBlock(conversationId, ready.size());
        long firstSeq = lastSeq - ready.size() + 1;
        for (int i = 0; i < ready.size(); i++) {
            if (!turnId.equals(redisTemplate.opsForValue().get(turnKey))) {
                return;
            }
            String cleaned = ready.get(i);
            Message charMsg = new Message();
            charMsg.setSeq(firstSeq + i);
            charMsg.setConversationId(conversationId);
            charMsg.setRole("ASSISTANT");
            charMsg.setCharacterId(reply.characterId());
            charMsg.setContent(cleaned);
            charMsg.setTokens(i == 0 ? reply.tokens() : null);
            messageMapper.insert(charMsg);

            sendToGroup(conversationId, GroupMessageResponse.builder()
                    .type("CHARACTER_MESSAGE")
                    .conversationId(conversationId)
                    .characterId(reply.characterId())
                    .characterName(reply.characterName())
                    .content(cleaned)
                    .build());

            notifyGroupUnread(userId, conversationId, reply.characterId(), reply.characterName(), cleaned);

            if (i < ready.size() - 1 && bubbleGapMs > 0) {
                if (!turnId.equals(redisTemplate.opsForValue().get(turnKey))) {
                    return;
                }
                try {
                    Thread.sleep(bubbleGapMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    private void notifyGroupUnread(Long userId,
                                   Long conversationId,
                                   Long characterId,
                                   String characterName,
                                   String content) {
        if (userId == null || conversationId == null) {
            return;
        }
        try {
            notificationService.notifyGroupMessage(userId, conversationId, characterId, characterName, content);
        } catch (Exception e) {
            log.debug("Group unread notify skipped: convId={}, reason={}", conversationId, e.getMessage());
        }
    }

    private String buildOtherCharactersContext(List<GroupMember> members, Character current, String outputLang) {
        List<Long> allIds = members.stream().map(GroupMember::getCharacterId).distinct().toList();
        List<Character> allChars = characterMapper.selectBatchIds(allIds);
        Map<Long, Character> charMap = allChars.stream()
                .collect(Collectors.toMap(Character::getId, c -> c, (a, b) -> a));
        StringBuilder sb = new StringBuilder();
        List<Character> others = new ArrayList<>();
        for (GroupMember m : members) {
            if (!m.getCharacterId().equals(current.getId())) {
                Character c = charMap.get(m.getCharacterId());
                if (c != null) others.add(c);
            }
        }
        if (others.isEmpty()) {
            return "";
        }
        String sep = switch (OutputLanguage.fromCode(outputLang)) {
            case EN -> ", ";
            default -> "、";
        };
        List<String> names = new ArrayList<>();
        for (Character c : others) {
            names.add(c.getName());
        }
        String nameLine = String.join(sep, names);

        return switch (OutputLanguage.fromCode(outputLang)) {
            case JA -> "ほかに " + nameLine + " がいる。必要なら話しかけていい。";
            case EN -> "Others here: " + nameLine + ". You may interact with them.";
            case ZH_TW -> "群裡還有: " + nameLine.replace(", ", "、") + "。你可以和他們互動。";
            case ZH -> "群里还有: " + nameLine.replace(", ", "、") + "。你可以和他们互动。";
        };
    }

    private Map<Long, Character> loadCharacterMap(List<GroupMember> members) {
        Set<Long> ids = members.stream().map(GroupMember::getCharacterId).collect(Collectors.toSet());
        Map<Long, Character> characterMap = new HashMap<>();
        if (ids.isEmpty()) {
            return characterMap;
        }
        for (Character c : characterMapper.selectBatchIds(ids)) {
            if (c != null) {
                characterMap.put(c.getId(), c);
            }
        }
        return characterMap;
    }

    private static Map<Long, String> toNameMap(Map<Long, Character> characterMap) {
        Map<Long, String> nameMap = new HashMap<>();
        if (characterMap == null || characterMap.isEmpty()) {
            return nameMap;
        }
        for (Map.Entry<Long, Character> entry : characterMap.entrySet()) {
            Character c = entry.getValue();
            if (c != null) {
                nameMap.put(entry.getKey(), c.getName());
            }
        }
        return nameMap;
    }

    private Map<Long, String> loadCharacterNameMap(List<GroupMember> members) {
        return toNameMap(loadCharacterMap(members));
    }

    private String getCharacterName(Long characterId, Map<Long, String> nameMap) {
        return nameMap.getOrDefault(characterId, "未知角色");
    }

    private Map<Long, String> buildMemberNameMap(List<GroupMember> members, Map<Long, String> nameMap) {
        Map<Long, String> result = new HashMap<>();
        for (GroupMember member : members) {
            result.put(member.getCharacterId(), getCharacterName(member.getCharacterId(), nameMap));
        }
        return result;
    }

    private Set<Long> extractMentionedCharacterIds(String content, List<GroupMember> members,
                                                    Map<Long, String> nameMap) {
        Set<Long> mentioned = new LinkedHashSet<>();
        if (content == null || content.isBlank()) {
            return mentioned;
        }
        for (GroupMember member : members) {
            String name = getCharacterName(member.getCharacterId(), nameMap);
            if (name != null && !name.isBlank() && content.contains("@" + name)) {
                mentioned.add(member.getCharacterId());
            }
        }
        return mentioned;
    }

    private String normalizeImplicitMentions(String content, List<GroupMember> members,
                                              Map<Long, String> nameMap) {
        if (content == null || content.isBlank() || members == null || members.isEmpty()) {
            return content;
        }
        String normalized = content;
        List<String> autoMentions = new ArrayList<>();
        for (GroupMember member : members) {
            String name = getCharacterName(member.getCharacterId(), nameMap);
            if (name == null || name.isBlank() || normalized.contains("@" + name)) {
                continue;
            }
            if (containsImplicitMention(normalized, name)) {
                autoMentions.add("@" + name);
            }
        }
        if (autoMentions.isEmpty()) {
            return normalized;
        }
        return String.join(" ", autoMentions) + " " + normalized;
    }

    private boolean containsImplicitMention(String content, String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        Pattern[] patterns = implicitMentionPatterns.computeIfAbsent(name, n -> {
            String escaped = Pattern.quote(n);
            return new Pattern[]{
                    Pattern.compile(escaped + IMPLICIT_PUNCTUATION + IMPLICIT_TRIGGER_WORDS),
                    Pattern.compile(IMPLICIT_ASK_PREFIX + IMPLICIT_PUNCTUATION + escaped),
                    Pattern.compile(escaped + IMPLICIT_PUNCTUATION + IMPLICIT_SENTENCE_END + "$")
            };
        });
        for (Pattern pattern : patterns) {
            if (pattern.matcher(content).find()) {
                return true;
            }
        }
        return false;
    }

    private String buildMentionContext(Character current,
                                       List<Message> historySnapshot,
                                       Map<Long, String> memberNameMap) {
        if (historySnapshot == null || historySnapshot.isEmpty()) {
            return "";
        }
        String selfMention = "@" + current.getName();
        List<String> mentionLines = new ArrayList<>();
        int start = Math.max(0, historySnapshot.size() - 12);
        for (int i = start; i < historySnapshot.size(); i++) {
            Message msg = historySnapshot.get(i);
            String content = msg.getContent();
            if (content == null || content.isBlank() || !content.contains(selfMention)) {
                continue;
            }
            String speaker;
            if ("USER".equalsIgnoreCase(msg.getRole())) {
                speaker = "用户";
            } else if (msg.getCharacterId() != null) {
                speaker = memberNameMap.getOrDefault(msg.getCharacterId(), "角色");
            } else {
                speaker = "系统";
            }
            String compact = content.length() > 120 ? content.substring(0, 120) + "..." : content;
            mentionLines.add("- " + speaker + " 提及你: " + compact);
        }
        if (mentionLines.isEmpty()) {
            return "";
        }
        return "\n\n=== 被 @ 提及上下文 ===\n"
                + "最近有人明确 @你，请优先回应这些提及：\n"
                + String.join("\n", mentionLines)
                + "\n如果你回复了某个对象，可使用 @角色名 指向对方。";
    }

    private String enhanceMentionsForAssistantReply(String content,
                                                    String speakerName,
                                                    List<GroupMember> members,
                                                    Map<Long, String> nameMap,
                                                    Long userId,
                                                    SendMessageRequest request,
                                                    List<Message> historySnapshot) {
        if (content == null || content.isBlank()) {
            return content;
        }
        if (containsAnyMention(content, members, nameMap)) {
            return content;
        }

        MentionDecision ruleDecision = decideMentionByRules(content, speakerName, members, nameMap);
        if (ruleDecision != null && ruleDecision.confidence() >= mentionJudgeThreshold) {
            return "@" + ruleDecision.target() + " " + content;
        }

        if (!mentionJudgeEnabled || !looksLikeDirectedUtterance(content)) {
            return content;
        }

        MentionDecision llmDecision = decideMentionByJudgeModel(
                content, speakerName, members, nameMap, userId, request, historySnapshot);
        if (llmDecision == null || !llmDecision.shouldMention()) {
            return content;
        }
        if (llmDecision.confidence() < mentionJudgeThreshold) {
            return content;
        }
        if (!isValidMentionTarget(llmDecision.target(), speakerName, members, nameMap)) {
            return content;
        }
        return "@" + llmDecision.target() + " " + content;
    }

    private MentionDecision decideMentionByRules(String content, String speakerName, List<GroupMember> members,
                                                  Map<Long, String> nameMap) {
        String normalized = content.toLowerCase(Locale.ROOT);
        for (GroupMember member : members) {
            String name = getCharacterName(member.getCharacterId(), nameMap);
            if (name == null || name.isBlank() || name.equals(speakerName)) {
                continue;
            }
            if (containsImplicitMention(content, name)) {
                return new MentionDecision(true, name, 0.95);
            }
            if (normalized.contains(name.toLowerCase(Locale.ROOT))
                    && (normalized.contains("？") || normalized.contains("?"))) {
                return new MentionDecision(true, name, 0.62);
            }
        }
        if (normalized.contains("你觉得呢")
                || normalized.contains("你怎么看")
                || normalized.contains("你呢")
                || normalized.contains("说说你的看法")
                || normalized.contains("你来回答")) {
            return new MentionDecision(true, "你", 0.88);
        }
        return null;
    }

    private MentionDecision decideMentionByJudgeModel(String content,
                                                      String speakerName,
                                                      List<GroupMember> members,
                                                      Map<Long, String> nameMap,
                                                      Long userId,
                                                      SendMessageRequest request,
                                                      List<Message> historySnapshot) {
        try {
            List<String> candidates = buildMentionCandidates(speakerName, members, nameMap);
            if (candidates.isEmpty()) {
                return null;
            }

            String ctx = buildJudgeContext(historySnapshot, mentionJudgeContextMessages, nameMap);
            // 文案与拼装顺序归 Python（app/prompts/group_mention_judge_{system,user}.md）。
            // 取不到就跳过本次 @ 决策：宁可少一个 @，也不在这里编兜底文案。
            if (promptPort == null) {
                return null;
            }
            String systemPrompt = promptPort.render("group_mention_judge_system", Map.of(), 0);
            if (systemPrompt == null || systemPrompt.isBlank()) {
                return null;
            }
            Map<String, Object> judgeVars = new HashMap<>();
            judgeVars.put("speaker", speakerName == null ? "" : speakerName);
            judgeVars.put("candidates", String.join("、", candidates));
            judgeVars.put("content", content == null ? "" : content);
            judgeVars.put("context", ctx);
            String userPrompt = promptPort.render("group_mention_judge_user", judgeVars, 0);
            if (userPrompt == null || userPrompt.isBlank()) {
                return null;
            }

            com.lianyu.service.agent.AgentChatResult result = agentBlockingPort.complete(
                    userId,
                    List.of(buildSystemMessage(systemPrompt), buildUserMessage(userPrompt)),
                    request.getProvider(), request.getModel(), 0.1, null);
            if (result == null || result.content() == null || result.content().isBlank()) {
                return null;
            }
            String json = extractJsonObject(result.content().trim());
            JsonNode root = objectMapper.readTree(json);
            boolean should = root.path("shouldMention").asBoolean(false);
            String target = root.path("target").asText("NONE");
            double confidence = root.path("confidence").asDouble(0.0);
            if ("NONE".equalsIgnoreCase(target)) {
                should = false;
            }
            return new MentionDecision(should, target, confidence);
        } catch (Exception e) {
            log.debug("mention judge skipped: {}", e.getMessage());
            return null;
        }
    }

    private List<String> buildMentionCandidates(String speakerName, List<GroupMember> members,
                                                Map<Long, String> nameMap) {
        List<String> candidates = new ArrayList<>();
        for (GroupMember member : members) {
            String name = getCharacterName(member.getCharacterId(), nameMap);
            if (name == null || name.isBlank() || name.equals(speakerName)) {
                continue;
            }
            candidates.add(name);
        }
        candidates.add("你");
        return candidates;
    }

    private String buildJudgeContext(List<Message> historySnapshot, int maxMessages, Map<Long, String> nameMap) {
        if (historySnapshot == null || historySnapshot.isEmpty()) {
            return "(空)";
        }
        int max = Math.max(1, maxMessages);
        int start = Math.max(0, historySnapshot.size() - max);
        List<String> lines = new ArrayList<>();
        for (int i = start; i < historySnapshot.size(); i++) {
            Message msg = historySnapshot.get(i);
            String role = "USER".equalsIgnoreCase(msg.getRole())
                    ? "用户"
                    : getCharacterName(msg.getCharacterId(), nameMap);
            String content = msg.getContent() == null ? "" : msg.getContent().trim();
            if (content.length() > 120) {
                content = content.substring(0, 120) + "...";
            }
            lines.add(role + ": " + content);
        }
        return String.join("\n", lines);
    }

    private boolean containsAnyMention(String content, List<GroupMember> members, Map<Long, String> nameMap) {
        if (content.contains("@你")) {
            return true;
        }
        for (GroupMember member : members) {
            String name = getCharacterName(member.getCharacterId(), nameMap);
            if (name != null && !name.isBlank() && content.contains("@" + name)) {
                return true;
            }
        }
        return false;
    }

    private boolean looksLikeDirectedUtterance(String content) {
        String lower = content.toLowerCase(Locale.ROOT);
        return lower.contains("？")
                || lower.contains("?")
                || lower.contains("觉得呢")
                || lower.contains("怎么看")
                || lower.contains("你呢")
                || lower.contains("请问")
                || lower.contains("问下")
                || lower.contains("说说看");
    }

    private boolean isValidMentionTarget(String target, String speakerName, List<GroupMember> members,
                                         Map<Long, String> nameMap) {
        if (target == null || target.isBlank()) {
            return false;
        }
        if ("你".equals(target)) {
            return true;
        }
        if (target.equals(speakerName)) {
            return false;
        }
        for (GroupMember member : members) {
            String name = getCharacterName(member.getCharacterId(), nameMap);
            if (target.equals(name)) {
                return true;
            }
        }
        return false;
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

    private String extractJsonObject(String text) {
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return text;
    }

    private static final String GROUP_HISTORY_TAG = "群聊-";

    private static final Pattern PARAGRAPH_SPLIT = Pattern.compile("\\n\\s*\\n+");

    private String formatOtherCharacterHistoryLine(String charName, String content) {
        String safeName = charName == null || charName.isBlank() ? "其他角色" : charName.trim();
        String safeContent = content == null ? "" : content;
        return "「" + GROUP_HISTORY_TAG + safeName + "」: " + safeContent;
    }

    private String filterInnerThoughtsForCharacter(Character character, String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        return InnerThoughtFilter.stripIfDisabled(text, CharacterPreferenceResolver.showInnerThoughts(character));
    }

    private String sanitizeGroupReply(String raw, String speakerName, List<GroupMember> members,
                                       Map<Long, String> nameMap) {
        if (raw == null || raw.isBlank()) {
            return raw;
        }

        String normalized = raw.replace("\r\n", "\n").trim();
        String[] paragraphs = PARAGRAPH_SPLIT.split(normalized);
        List<String> cleanedParagraphs = new ArrayList<>();
        for (String paragraph : paragraphs) {
            String[] lines = paragraph.split("\\n");
            List<String> kept = new ArrayList<>();
            for (String line : lines) {
                String t = line.trim();
                if (t.isEmpty()) {
                    continue;
                }
                if (lineAttributedToOtherSpeaker(t, speakerName, members, nameMap)) {
                    continue;
                }
                String stripped = stripLeadingSpeakerLabel(t, speakerName);
                if (!stripped.isBlank()) {
                    kept.add(stripped);
                }
            }
            if (!kept.isEmpty()) {
                cleanedParagraphs.add(replySplitter.collapseSoftLineBreaks(String.join("", kept)));
            }
        }
        if (cleanedParagraphs.isEmpty()) {
            return "";
        }
        return String.join("\n\n", cleanedParagraphs);
    }

    private boolean lineAttributedToOtherSpeaker(String line, String speakerName, List<GroupMember> members,
                                                  Map<Long, String> nameMap) {
        String t = line.trim();
        String groupSpeaker = extractGroupHistorySpeaker(t);
        if (groupSpeaker != null) {
            return !groupSpeaker.equals(speakerName) && !groupSpeaker.equalsIgnoreCase(speakerName);
        }
        String colonSpeaker = extractColonSpeakerPrefix(t);
        if (colonSpeaker == null) {
            return false;
        }
        if (colonSpeaker.equals(speakerName) || colonSpeaker.equalsIgnoreCase(speakerName)) {
            return false;
        }
        for (GroupMember member : members) {
            String name = getCharacterName(member.getCharacterId(), nameMap);
            if (name == null || name.isBlank() || name.equals(speakerName)) {
                continue;
            }
            if (colonSpeaker.equals(name) || colonSpeaker.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return looksLikeSpeakerScriptLine(colonSpeaker);
    }

    private String extractGroupHistorySpeaker(String line) {
        if (line == null || !line.startsWith("「" + GROUP_HISTORY_TAG)) {
            return null;
        }
        int end = line.indexOf('」');
        if (end <= GROUP_HISTORY_TAG.length() + 1) {
            return null;
        }
        return line.substring(GROUP_HISTORY_TAG.length() + 1, end).trim();
    }

    private String extractColonSpeakerPrefix(String line) {
        String t = line.trim();
        int colonIdx = t.indexOf('：');
        int asciiColon = t.indexOf(':');
        int splitAt = colonIdx >= 0 && asciiColon >= 0 ? Math.min(colonIdx, asciiColon)
                : Math.max(colonIdx, asciiColon);
        if (splitAt <= 0 || splitAt > 32) {
            return null;
        }
        String prefix = t.substring(0, splitAt).trim();
        if (prefix.isEmpty()) {
            return null;
        }
        if (prefix.endsWith("说") || prefix.endsWith("说道")) {
            prefix = prefix.replaceAll("(说|说道)$", "").trim();
        }
        return prefix.isEmpty() ? null : prefix;
    }

    private boolean looksLikeSpeakerScriptLine(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return false;
        }
        return prefix.length() <= 16
                && !prefix.contains(" ")
                && !prefix.contains("，")
                && !prefix.contains(",");
    }

    private String stripLeadingSpeakerLabel(String line, String speakerName) {
        String t = line.trim();
        if (speakerName == null || speakerName.isBlank()) {
            return t;
        }
        String groupSpeaker = extractGroupHistorySpeaker(t);
        if (groupSpeaker != null) {
            int colonIdx = t.indexOf('：');
            int asciiColon = t.indexOf(':');
            int splitAt = colonIdx >= 0 && asciiColon >= 0 ? Math.min(colonIdx, asciiColon)
                    : Math.max(colonIdx, asciiColon);
            if (splitAt > 0 && splitAt < t.length() - 1) {
                return t.substring(splitAt + 1).trim();
            }
            return "";
        }
        if (t.startsWith(speakerName + "：")) {
            return t.substring(speakerName.length() + 1).trim();
        }
        if (t.startsWith(speakerName + ":")) {
            return t.substring(speakerName.length() + 1).trim();
        }
        if (t.startsWith(speakerName + "说：")) {
            return t.substring(speakerName.length() + 2).trim();
        }
        if (t.startsWith(speakerName + "说道：")) {
            return t.substring(speakerName.length() + 3).trim();
        }
        if (t.startsWith("「" + GROUP_HISTORY_TAG + speakerName + "」:")) {
            return t.substring(("「" + GROUP_HISTORY_TAG + speakerName + "」:").length()).trim();
        }
        if (t.startsWith("「" + GROUP_HISTORY_TAG + speakerName + "」：")) {
            return t.substring(("「" + GROUP_HISTORY_TAG + speakerName + "」：").length()).trim();
        }
        return t;
    }

    private record MentionDecision(boolean shouldMention, String target, double confidence) {}

    private record CharacterReply(Long characterId, String characterName, String content, Integer tokens) {}

    private void sendToGroup(Long conversationId, GroupMessageResponse response) {
        messagingTemplate.convertAndSend(
                "/topic/group/" + conversationId, response);
    }

    private List<Message> getRecentMessages(Long conversationId, int limit) {
        List<Message> messages = messageMapper.selectList(new LambdaQueryWrapper<Message>()
                .eq(Message::getConversationId, conversationId)
                .orderByDesc(Message::getSeq)
                .last("LIMIT " + limit));
        java.util.Collections.reverse(messages);
        return messages;
    }

    private long getNextSeq(Long conversationId) {
        return reserveSeqBlock(conversationId, 1);
    }

    private long reserveSeqBlock(Long conversationId, int count) {
        int safeCount = Math.max(1, count);
        Long lastSeq = redisTemplate.opsForValue().increment(SEQ_KEY_PREFIX + conversationId, safeCount);
        if (lastSeq == null) {
            throw new IllegalStateException("Failed to reserve message seq for conversation " + conversationId);
        }
        return lastSeq;
    }

    private Conversation findOwned(Long userId, Long conversationId) {
        Conversation conversation = conversationMapper.selectById(conversationId);
        if (conversation == null || !conversation.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.CONVERSATION_NOT_FOUND);
        }
        return conversation;
    }

}
