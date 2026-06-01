package com.lianyu.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lianyu.common.constant.AiConstants;
import com.lianyu.common.base.ErrorCode;
import com.lianyu.common.util.UserInputSanitizer;
import com.lianyu.common.exception.BusinessException;
import com.lianyu.common.i18n.OutputLanguage;
import com.lianyu.dao.entity.Character;
import com.lianyu.dao.entity.Conversation;
import com.lianyu.dao.entity.GroupMember;
import com.lianyu.dao.entity.Message;
import com.lianyu.dao.mapper.CharacterMapper;
import com.lianyu.dao.mapper.ConversationMapper;
import com.lianyu.dao.mapper.GroupMemberMapper;
import com.lianyu.dao.mapper.MessageMapper;
import com.lianyu.service.dto.*;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import jakarta.annotation.Resource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskExecutor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class GroupChatService {

    private final ConversationMapper conversationMapper;
    private final GroupMemberMapper groupMemberMapper;
    private final CharacterMapper characterMapper;
    private final MessageMapper messageMapper;
    private final AiChatService aiChatService;
    private final CharacterPromptBuilder promptBuilder;
    private final OutputLanguageService outputLanguageService;
    private final MemoryRetriever memoryRetriever;
    private final SimpMessagingTemplate messagingTemplate;
    private final StringRedisTemplate redisTemplate;
    private final FileStorageService fileStorageService;
    private final CharacterChatBehaviorResolver chatBehaviorResolver;
    private final AssistantReplySplitter replySplitter;
    private final NotificationService notificationService;
    private final ObjectMapper objectMapper;
    @Resource(name = "groupChatExecutor")
    private TaskExecutor taskExecutor;

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
    @Value("${lianyu.group.mention-judge.model:deepseek-chat}")
    private String mentionJudgeModel;
    @Value("${lianyu.group.mention-judge.max-context-messages:6}")
    private int mentionJudgeContextMessages;

    private static final String SEQ_KEY_PREFIX = "msg_seq:";
    private static final String TURN_KEY_PREFIX = "group_chat:turn:";
    private static final Duration TURN_TTL = Duration.ofMinutes(5);

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

        int order = 0;
        for (Long characterId : characterIds) {
            Character character = characterMapper.selectById(characterId);
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

        String mentionTarget = pickProactiveMentionTarget(character, members);
        String warmHint = (contextHint == null || contextHint.isBlank()) ? "最近群聊" : contextHint.trim();

        Map<Long, String> nameMap = buildMemberNameMap(members);
        String memoryCtx = memoryRetriever.retrieveContext(character.getId(), userId, warmHint);
        CharacterChatBehavior behavior = chatBehaviorResolver.resolve(character);
        int maxPieces = behavior.maxRepliesPerTurn();
        String mentionCtx = buildMentionContext(character, historySnapshot, nameMap);

        String outputLang = outputLanguageService.resolveForRequest(userId, warmHint);
        String othersLine = buildOtherCharactersContext(members, character, outputLang);
        String systemPrompt = promptBuilder.buildSystemPrompt(character, memoryCtx, outputLang);
        systemPrompt = promptBuilder.appendGroupChatInstructions(
                systemPrompt,
                character,
                maxPieces,
                othersLine,
                mentionCtx,
                outputLang);

        AiChatRequest aiReq = new AiChatRequest();
        aiReq.setProvider(AiConstants.PLATFORM_PROVIDER);
        aiReq.setTemperature(0.85);

        List<MessageDto> allMsgs = new ArrayList<>();
        MessageDto sysDto = new MessageDto();
        sysDto.setRole("system");
        sysDto.setContent(systemPrompt);
        allMsgs.add(sysDto);

        for (Message msg : historySnapshot) {
            MessageDto dto = new MessageDto();
            dto.setRole(msg.getRole().toLowerCase());
            String content = msg.getContent();
            if (msg.getCharacterId() != null && !msg.getCharacterId().equals(character.getId())) {
                String charName = getCharacterName(msg.getCharacterId());
                content = charName + ": " + content;
            }
            dto.setContent(content);
            allMsgs.add(dto);
        }

        String proactiveInstruction = switch (OutputLanguage.fromCode(outputLang)) {
            case JA -> """
                    いまグループチャットで自発的に一言（または短い連投）してください。ユーザーが今まさに送ったわけではありません。
                    必須：各メッセージはできるだけ @%s で始める（@あなた＝ユーザー、または @他キャラ名）。
                    1〜%d 件の短い発言、空行区切り。自然に、履歴に沿って。機械的な挨拶は避ける。
                    """.formatted(mentionTarget, maxPieces);
            case EN -> """
                    Speak up proactively in this group chat (the user did not just send a new message).
                    Required: each piece should start with @%s (@你 = the user, or another member's name).
                    1–%d short messages, blank line separated. Stay in character; follow recent context.
                    """.formatted(mentionTarget, maxPieces);
            case ZH_TW -> """
                    你現在要在群聊裡主動發言（不是用戶剛發了一條需要你回覆）。
                    必須：每條訊息盡量以 @%s 開頭（@你 表示用戶，或 @其他角色名 表示群裡另一位角色）。
                    共 1～%d 條短訊息，多條用空行分隔；語氣自然，結合最近上下文，不要機械問候。
                    可以關心用戶，也可以接其他角色的話茬。
                    """.formatted(mentionTarget, maxPieces);
            case ZH -> """
                    你现在要在群聊里主动发言（不是用户刚发了一条需要你回复）。
                    必须：每条消息尽量以 @%s 开头（@你 表示用户，或 @其他角色名 表示群里另一位角色）。
                    共 1～%d 条短消息，多条用空行分隔；语气自然，结合最近上下文，不要机械问候。
                    可以关心用户，也可以接其他角色的话茬。
                    """.formatted(mentionTarget, maxPieces);
        };
        allMsgs.add(buildUserMessage(proactiveInstruction + "\n\n最近气氛参考：" + warmHint));
        aiReq.setMessages(allMsgs);

        try {
            ChatResult result = aiChatService.chatBlocking(userId, aiReq);
            String cleanedContent = sanitizeGroupReply(result.getContent(), character.getName(), members);
            if (cleanedContent == null || cleanedContent.isBlank()) {
                return false;
            }
            CharacterReply reply = new CharacterReply(
                    character.getId(), character.getName(), cleanedContent, result.getTotalTokens());

            SendMessageRequest platformReq = new SendMessageRequest();
            platformReq.setProvider(AiConstants.PLATFORM_PROVIDER);
            persistProactiveGroupReplies(userId, conversationId, reply, members, mentionTarget, platformReq);
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
        for (GroupMember member : members) {
            Character character = characterMapper.selectById(member.getCharacterId());
            if (character != null) {
                result.add(CharacterResponse.builder()
                        .id(character.getId())
                        .name(character.getName())
                        .avatarUrl(fileStorageService.resolvePublicUrl(character.getAvatarUrl()))
                        .build());
            }
        }
        return result;
    }

    public void handleGroupMessage(Long userId, Long conversationId, SendMessageRequest request) {
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

        long userSeq = getNextSeq(conversationId);

        Message userMsg = new Message();
        userMsg.setSeq(userSeq);
        userMsg.setConversationId(conversationId);
        userMsg.setRole("USER");
        UserInputSanitizer.SanitizedUserText sanitized = UserInputSanitizer.sanitizeChatMessage(
                request.getContent() != null ? request.getContent() : "");
        String normalizedUserContent = normalizeImplicitMentions(sanitized.storedText(), members);
        request.setContent(normalizedUserContent);
        request.setModelContentForAi(UserInputSanitizer.wrapStoredTextForModel(normalizedUserContent));
        userMsg.setContent(normalizedUserContent);
        messageMapper.insert(userMsg);
        final Long currentUserMsgId = userMsg.getId();
        Set<Long> userMentionedIds = extractMentionedCharacterIds(normalizedUserContent, members);

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
                List<CompletableFuture<CharacterReply>> futures = new ArrayList<>();
                for (GroupMember member : roundMembers) {
                    futures.add(CompletableFuture.supplyAsync(
                            () -> generateCharacterReply(member, members, userId, request, historySnapshot,
                                    currentUserMsgId), taskExecutor));
                }

                List<CompletableFuture<CharacterReply>> pending = new ArrayList<>(futures);
                int repliedCount = 0;
                while (!pending.isEmpty()) {
                    CompletableFuture.anyOf(pending.toArray(new CompletableFuture[0])).join();
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
                        persistAndBroadcastReplies(conversationId, reply, members, userId, request, historySnapshot);
                        repliedCount++;
                    }
                }

                if (repliedCount == 0) {
                    break;
                }
            }

            if (turnId.equals(redisTemplate.opsForValue().get(turnKey))) {
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
            Long currentUserMsgId) {
        try {
            Character character = characterMapper.selectById(member.getCharacterId());
            if (character == null) {
                return null;
            }

            Map<Long, String> nameMap = buildMemberNameMap(members);
            String memoryCtx = memoryRetriever.retrieveContext(character.getId(), userId, request.getContent());
            CharacterChatBehavior behavior = chatBehaviorResolver.resolve(character);
            int maxPieces = behavior.maxRepliesPerTurn();
            String mentionCtx = buildMentionContext(character, historySnapshot, nameMap);

            String outputLang = outputLanguageService.resolveForRequest(userId, request.getContent());
            String othersLine = buildOtherCharactersContext(members, character, outputLang);
            String systemPrompt = promptBuilder.buildSystemPrompt(character, memoryCtx, outputLang);
            systemPrompt = promptBuilder.appendGroupChatInstructions(
                    systemPrompt,
                    character,
                    maxPieces,
                    othersLine,
                    mentionCtx,
                    outputLang);

            AiChatRequest aiReq = new AiChatRequest();
            aiReq.setProvider(request.getProvider());
            aiReq.setModel(request.getModel());
            aiReq.setTemperature(request.getTemperature());

            List<MessageDto> allMsgs = new ArrayList<>();
            MessageDto sysDto = new MessageDto();
            sysDto.setRole("system");
            sysDto.setContent(systemPrompt);
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
                    String charName = getCharacterName(msg.getCharacterId());
                    content = charName + ": " + content;
                }
                dto.setContent(content);
                allMsgs.add(dto);
            }
            aiReq.setMessages(allMsgs);

            ChatResult result = aiChatService.chatBlocking(userId, aiReq);
            String cleanedContent = sanitizeGroupReply(result.getContent(), character.getName(), members);
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
                                              SendMessageRequest request) {
        Character character = characterMapper.selectById(reply.characterId());
        CharacterChatBehavior behavior = chatBehaviorResolver.resolve(character);
        List<String> pieces = replySplitter.split(reply.content(), behavior.maxRepliesPerTurn());
        if (pieces.isEmpty()) {
            return;
        }

        for (int i = 0; i < pieces.size(); i++) {
            String piece = pieces.get(i);
            if (piece == null || piece.isBlank()) {
                continue;
            }
            String cleaned = sanitizeGroupReply(piece, reply.characterName(), members);
            if (cleaned == null || cleaned.isBlank()) {
                continue;
            }
            cleaned = ensureProactiveMentionPrefix(cleaned, preferredMentionTarget, members);

            long charSeq = getNextSeq(conversationId);
            Message charMsg = new Message();
            charMsg.setSeq(charSeq);
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

            if (i < pieces.size() - 1 && bubbleGapMs > 0) {
                try {
                    Thread.sleep(bubbleGapMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    private String pickProactiveMentionTarget(Character speaker, List<GroupMember> members) {
        List<String> targets = new ArrayList<>();
        targets.add("你");
        for (GroupMember member : members) {
            if (member.getCharacterId().equals(speaker.getId())) {
                continue;
            }
            String name = getCharacterName(member.getCharacterId());
            if (name != null && !name.isBlank()) {
                targets.add(name);
            }
        }
        return targets.get(ThreadLocalRandom.current().nextInt(targets.size()));
    }

    private String ensureProactiveMentionPrefix(String content,
                                                  String preferredTarget,
                                                  List<GroupMember> members) {
        if (content == null || content.isBlank()) {
            return content;
        }
        if (containsAnyMention(content, members)) {
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
                                            List<Message> historySnapshot) {
        Character character = characterMapper.selectById(reply.characterId());
        CharacterChatBehavior behavior = chatBehaviorResolver.resolve(character);
        List<String> pieces = replySplitter.split(reply.content(), behavior.maxRepliesPerTurn());
        if (pieces.isEmpty()) {
            return;
        }

        for (int i = 0; i < pieces.size(); i++) {
            String piece = pieces.get(i);
            if (piece == null || piece.isBlank()) {
                continue;
            }
            String cleaned = sanitizeGroupReply(piece, reply.characterName(), members);
            if (cleaned == null || cleaned.isBlank()) {
                continue;
            }
            cleaned = enhanceMentionsForAssistantReply(
                    cleaned, reply.characterName(), members, userId, request, historySnapshot);

            long charSeq = getNextSeq(conversationId);
            Message charMsg = new Message();
            charMsg.setSeq(charSeq);
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

            if (i < pieces.size() - 1 && bubbleGapMs > 0) {
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
        StringBuilder sb = new StringBuilder();
        List<Character> others = new ArrayList<>();
        for (GroupMember m : members) {
            if (!m.getCharacterId().equals(current.getId())) {
                Character c = characterMapper.selectById(m.getCharacterId());
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

    private String getCharacterName(Long characterId) {
        Character c = characterMapper.selectById(characterId);
        return c != null ? c.getName() : "未知角色";
    }

    private Map<Long, String> buildMemberNameMap(List<GroupMember> members) {
        Map<Long, String> nameMap = new HashMap<>();
        for (GroupMember member : members) {
            nameMap.put(member.getCharacterId(), getCharacterName(member.getCharacterId()));
        }
        return nameMap;
    }

    private Set<Long> extractMentionedCharacterIds(String content, List<GroupMember> members) {
        Set<Long> mentioned = new LinkedHashSet<>();
        if (content == null || content.isBlank()) {
            return mentioned;
        }
        for (GroupMember member : members) {
            String name = getCharacterName(member.getCharacterId());
            if (name != null && !name.isBlank() && content.contains("@" + name)) {
                mentioned.add(member.getCharacterId());
            }
        }
        return mentioned;
    }

    private String normalizeImplicitMentions(String content, List<GroupMember> members) {
        if (content == null || content.isBlank() || members == null || members.isEmpty()) {
            return content;
        }
        String normalized = content;
        List<String> autoMentions = new ArrayList<>();
        for (GroupMember member : members) {
            String name = getCharacterName(member.getCharacterId());
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
        String escapedName = Pattern.quote(name);
        String triggerWords = "(觉得呢|怎么看|你呢|怎么看呢|什么看法|说说看|回答下|回复下)";
        String punctuation = "[，。！？!?,\\s]*";

        return Pattern.compile(escapedName + punctuation + triggerWords).matcher(content).find()
                || Pattern.compile("(问问|请问|问下|让)" + punctuation + escapedName).matcher(content).find()
                || Pattern.compile(escapedName + punctuation + "(吧|呀|吗)[？?]?$").matcher(content).find();
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
                                                    Long userId,
                                                    SendMessageRequest request,
                                                    List<Message> historySnapshot) {
        if (content == null || content.isBlank()) {
            return content;
        }
        if (containsAnyMention(content, members)) {
            return content;
        }

        MentionDecision ruleDecision = decideMentionByRules(content, speakerName, members);
        if (ruleDecision != null && ruleDecision.confidence() >= mentionJudgeThreshold) {
            return "@" + ruleDecision.target() + " " + content;
        }

        if (!mentionJudgeEnabled || !looksLikeDirectedUtterance(content)) {
            return content;
        }

        MentionDecision llmDecision = decideMentionByJudgeModel(
                content, speakerName, members, userId, request, historySnapshot);
        if (llmDecision == null || !llmDecision.shouldMention()) {
            return content;
        }
        if (llmDecision.confidence() < mentionJudgeThreshold) {
            return content;
        }
        if (!isValidMentionTarget(llmDecision.target(), speakerName, members)) {
            return content;
        }
        return "@" + llmDecision.target() + " " + content;
    }

    private MentionDecision decideMentionByRules(String content, String speakerName, List<GroupMember> members) {
        String normalized = content.toLowerCase(Locale.ROOT);
        for (GroupMember member : members) {
            String name = getCharacterName(member.getCharacterId());
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
                                                      Long userId,
                                                      SendMessageRequest request,
                                                      List<Message> historySnapshot) {
        try {
            List<String> candidates = buildMentionCandidates(speakerName, members);
            if (candidates.isEmpty()) {
                return null;
            }

            String ctx = buildJudgeContext(historySnapshot, mentionJudgeContextMessages);
            String systemPrompt = """
                    你是“群聊@提及裁决器”。只做一件事：判断当前消息是否应该添加一个@对象。
                    规则：
                    1) 只允许从候选列表中选择1个目标，或选择 NONE；
                    2) 若语义没有明显点名对象，返回 NONE；
                    3) 不要改写原句，不要输出解释；
                    4) 仅输出 JSON：{"shouldMention":true/false,"target":"候选名或NONE","confidence":0~1}
                    """;
            String userPrompt = "说话者: " + speakerName
                    + "\n候选目标: " + String.join("、", candidates)
                    + "\n当前消息: " + content
                    + "\n最近上下文:\n" + ctx;

            AiChatRequest judgeReq = new AiChatRequest();
            judgeReq.setProvider(AiConstants.PLATFORM_PROVIDER);
            judgeReq.setModel(mentionJudgeModel);
            judgeReq.setTemperature(0.1);
            judgeReq.setMessages(List.of(
                    buildSystemMessage(systemPrompt),
                    buildUserMessage(userPrompt)
            ));

            ChatResult result = aiChatService.chatBlocking(userId, judgeReq);
            if (result == null || result.getContent() == null || result.getContent().isBlank()) {
                return null;
            }
            String json = extractJsonObject(result.getContent().trim());
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

    private List<String> buildMentionCandidates(String speakerName, List<GroupMember> members) {
        List<String> candidates = new ArrayList<>();
        for (GroupMember member : members) {
            String name = getCharacterName(member.getCharacterId());
            if (name == null || name.isBlank() || name.equals(speakerName)) {
                continue;
            }
            candidates.add(name);
        }
        candidates.add("你");
        return candidates;
    }

    private String buildJudgeContext(List<Message> historySnapshot, int maxMessages) {
        if (historySnapshot == null || historySnapshot.isEmpty()) {
            return "(空)";
        }
        int max = Math.max(1, maxMessages);
        int start = Math.max(0, historySnapshot.size() - max);
        List<String> lines = new ArrayList<>();
        for (int i = start; i < historySnapshot.size(); i++) {
            Message msg = historySnapshot.get(i);
            String role = "USER".equalsIgnoreCase(msg.getRole()) ? "用户" : getCharacterName(msg.getCharacterId());
            String content = msg.getContent() == null ? "" : msg.getContent().trim();
            if (content.length() > 120) {
                content = content.substring(0, 120) + "...";
            }
            lines.add(role + ": " + content);
        }
        return String.join("\n", lines);
    }

    private boolean containsAnyMention(String content, List<GroupMember> members) {
        if (content.contains("@你")) {
            return true;
        }
        for (GroupMember member : members) {
            String name = getCharacterName(member.getCharacterId());
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

    private boolean isValidMentionTarget(String target, String speakerName, List<GroupMember> members) {
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
            String name = getCharacterName(member.getCharacterId());
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

    private String sanitizeGroupReply(String raw, String speakerName, List<GroupMember> members) {
        if (raw == null || raw.isBlank()) {
            return raw;
        }

        String normalized = raw.replace("\r\n", "\n").trim();
        String[] paragraphs = normalized.split("\\n\\s*\\n+");
        List<String> cleanedParagraphs = new ArrayList<>();
        for (String paragraph : paragraphs) {
            String[] lines = paragraph.split("\\n");
            List<String> kept = new ArrayList<>();
            for (String line : lines) {
                String t = line.trim();
                if (t.isEmpty()) {
                    continue;
                }
                if (!lineAttributedToOtherSpeaker(t, speakerName, members)) {
                    kept.add(stripLeadingSpeakerLabel(t, speakerName));
                }
            }
            if (!kept.isEmpty()) {
                cleanedParagraphs.add(replySplitter.collapseSoftLineBreaks(String.join("", kept)));
            }
        }
        // 全是「别的角色名: 台词」的剧本行时，不要原样塞回当前角色气泡
        if (cleanedParagraphs.isEmpty()) {
            return "";
        }
        return String.join("\n\n", cleanedParagraphs);
    }

    private boolean lineAttributedToOtherSpeaker(String line, String speakerName, List<GroupMember> members) {
        String t = line.trim();
        int colonIdx = t.indexOf('：');
        int asciiColon = t.indexOf(':');
        int splitAt = colonIdx >= 0 && asciiColon >= 0 ? Math.min(colonIdx, asciiColon)
                : Math.max(colonIdx, asciiColon);
        if (splitAt <= 0 || splitAt > 24) {
            return false;
        }
        String prefix = t.substring(0, splitAt).trim();
        if (prefix.isEmpty() || prefix.equals(speakerName)) {
            return false;
        }
        for (GroupMember member : members) {
            String name = getCharacterName(member.getCharacterId());
            if (name == null || name.isBlank() || name.equals(speakerName)) {
                continue;
            }
            if (prefix.equals(name) || prefix.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return true;
    }

    private String stripLeadingSpeakerLabel(String line, String speakerName) {
        String t = line.trim();
        if (speakerName == null || speakerName.isBlank()) {
            return t;
        }
        if (t.startsWith(speakerName + "：")) {
            return t.substring(speakerName.length() + 1).trim();
        }
        if (t.startsWith(speakerName + ":")) {
            return t.substring(speakerName.length() + 1).trim();
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
        return redisTemplate.opsForValue().increment(SEQ_KEY_PREFIX + conversationId);
    }

    private Conversation findOwned(Long userId, Long conversationId) {
        Conversation conversation = conversationMapper.selectById(conversationId);
        if (conversation == null || !conversation.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.CONVERSATION_NOT_FOUND);
        }
        return conversation;
    }
}
