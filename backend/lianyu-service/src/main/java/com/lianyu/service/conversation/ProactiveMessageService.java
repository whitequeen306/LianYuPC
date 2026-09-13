package com.lianyu.service.conversation;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lianyu.common.base.ErrorCode;
import com.lianyu.common.exception.BusinessException;
import com.lianyu.dao.entity.Character;
import com.lianyu.dao.entity.CharacterSquareTemplate;
import com.lianyu.dao.entity.Conversation;
import com.lianyu.dao.entity.Message;
import com.lianyu.dao.entity.User;
import com.lianyu.dao.mapper.CharacterMapper;
import com.lianyu.dao.mapper.CharacterSquareTemplateMapper;
import com.lianyu.dao.mapper.ConversationMapper;
import com.lianyu.dao.mapper.MessageMapper;
import com.lianyu.dao.mapper.UserMapper;
import com.lianyu.service.ai.AiChatService;
import com.lianyu.service.ai.PetMeetVoiceCatalog;
import com.lianyu.service.ai.PetVoiceRegistry;
import com.lianyu.service.character.UserAddressingResolver;
import com.lianyu.service.character.CharacterChatBehavior;
import com.lianyu.service.character.CharacterChatBehaviorResolver;
import com.lianyu.service.dto.AiChatRequest;
import com.lianyu.service.dto.ChatResult;
import com.lianyu.service.dto.MessageDto;
import com.lianyu.service.dto.MessageResponse;
import com.lianyu.service.dto.VaultEntryResponse;
import com.lianyu.service.graph.ChatTurnFacade;
import com.lianyu.service.graph.ChatTurnCommand;
import com.lianyu.service.graph.ChatTurnResult;
import com.lianyu.ai.graph.ChatTurnScene;
import com.lianyu.service.memory.MemoryRetriever;
import com.lianyu.service.memory.MemoryWriter;
import com.lianyu.service.notification.NotificationService;
import com.lianyu.service.relationship.RelationshipStateService;
import com.lianyu.service.tools.ChatToolContext;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 主动消息 / 破冰 / 固定语音 / 城市变更关心（从 ConversationService 拆出，docs/refactor-plan-coupling.md 项5）。
 *
 * <p>依赖方向：ProactiveMessageService → ConversationService（复用会话归属校验、seq、消息落库等
 * 共享辅助），反向无依赖。慢调用（AI / chatTurnFacade.invokeBlocking）一律在事务外，
 * 只在最后一步「防重 + 落库 + 通知」进事务——persist 方法经由 self 代理调用，
 * 使 {@code @Transactional} 真正生效（原先同类内直调被 Spring 代理绕过，事务注解形同虚设）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProactiveMessageService {

    private static final String COLD_OPEN_LOCK_PREFIX = "coldopen:lock:";
    private static final String FIXED_VOICE_ENTER_PREFIX = "chat:fixed-voice:enter:";
    private static final String FIXED_VOICE_SLOT_PREFIX = "chat:fixed-voice:slot:";
    private static final java.time.ZoneId FIXED_VOICE_ZONE = java.time.ZoneId.of("Asia/Shanghai");
    /** Re-enter chat voice at most once per this TTL. */
    private static final java.time.Duration ENTER_VOICE_COOLDOWN = java.time.Duration.ofHours(6);
    /** Skip enter voice if last activity was more recent than this. */
    private static final long ENTER_MIN_IDLE_MINUTES = 20;


    private String appendCityChangeContext(String basePrompt, String previousCity, String newCity) {
        return basePrompt + """


                === 用户城市变更（最高优先级，权威事实） ===
                """ + "用户当前现实所在城市：" + newCity + "\n"
                + "（刚才从 " + previousCity + " 变更而来。）\n\n"
                + "对话历史里若仍写用户还在 " + previousCity + " 或基于旧城市的天气/地点，一律视为过时信息，必须以本条为准。\n"
                + "你本次主动开口就是为了关心用户这次换城市，不要假装用户还在旧城市。";
    }

    /**
     * Trailing user turn for proactive/cold-open paths that end on assistant history.
     * Must stay short and clearly non-user; task details belong in system.
     */
    private static final String PROACTIVE_USER_TRIGGER = "（系统触发主动发言，用户未发送新消息。）";

    /** System-only task block for scheduled proactive chat. */
    private static String buildProactiveTaskSystemSuffix(int maxPieces) {
        int pieces = Math.max(1, maxPieces);
        return """


                === 主动发言任务（系统指令 · 不是用户发来的消息）===
                用户此刻没有发送任何新消息；你是在定时主动关心用户。
                请发 1～%d 条短消息（符合性格；多条用空行分隔）。
                语气自然，可围绕最近上下文或设定关心用户；不要重复历史原话，不要机械问候。

                硬性禁止：
                1. 禁止假装用户刚刚发了乱码、报错、代码、系统日志、奇怪「指令/要求」，或任何未在历史中真实出现的用户消息。
                2. 禁止把本系统任务、编号要求、或 prompt 片段当作用户输入来吐槽/复述。
                3. 历史里提过的旧事件（如设备/服务器故障）除非用户刚刚主动再提，否则不要当成此刻仍在发生，更不要说「又收到乱码」。
                """.formatted(pieces);
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



    private final ConversationService conversationService;
    private final ConversationMapper conversationMapper;
    private final MessageMapper messageMapper;
    private final CharacterMapper characterMapper;
    private final CharacterSquareTemplateMapper squareTemplateMapper;
    private final UserMapper userMapper;
    private final AiChatService aiChatService;
    private final ChatTurnFacade chatTurnFacade;
    private final PetMeetVoiceCatalog petMeetVoiceCatalog;
    private final PetVoiceRegistry petVoiceRegistry;
    private final MemoryRetriever memoryRetriever;
    private final MemoryWriter memoryWriter;
    private final StringRedisTemplate redisTemplate;
    private final CharacterChatBehaviorResolver chatBehaviorResolver;
    private final NotificationService notificationService;
    private final RelationshipStateService relationshipStateService;
    private final ProactiveUnrepliedThrottle proactiveUnrepliedThrottle;

    /** persistX 小事务经由代理调用才能让 @Transactional 生效（@Lazy 自注入打破环）。 */
    @Lazy
    @Autowired
    private ProactiveMessageService self;

    @Lazy
    @Autowired
    private SingleChatOpeningScheduler singleChatOpeningScheduler;

    @Value("${lianyu.ai.context-window:20}")
    private int contextWindow;

    // 故意不加 @Transactional：invokeBlocking 会调 AI，长事务占住 Hikari 连接拖死前台。
    // 只在最后一步「防重 + 落库」进事务。
    public List<MessageResponse> sendProactiveMessage(Long userId, Long conversationId, String hint) {
        VaultEntryResponse userVault = conversationService.resolveUserTextVaultOrNull(userId);
        if (userVault == null) {
            log.debug("Proactive message skipped: no user text model, userId={}, convId={}",
                    userId, conversationId);
            return List.of();
        }
        Conversation conversation = conversationService.findOwned(userId, conversationId);
        Character character = characterMapper.selectById(conversation.getCharacterId());
        if (character == null) {
            throw new BusinessException(ErrorCode.CHARACTER_NOT_FOUND);
        }
        conversationService.ensureCharacterAvailableForProactive(character);

        List<Message> history = conversationService.getRecentMessages(conversationId, contextWindow);
        CharacterChatBehavior behavior = chatBehaviorResolver.resolve(character);
        int maxPieces = behavior.maxRepliesPerTurn();
        List<MessageDto> prepared = new ArrayList<>();
        prepared.add(conversationService.buildSystemMessage(""));
        for (Message msg : history) {
            MessageDto dto = new MessageDto();
            dto.setRole(msg.getRole().toLowerCase());
            dto.setContent(msg.getContent());
            prepared.add(dto);
        }
        // Providers often require a trailing user turn; keep it a short system trigger only.
        // Long task text must stay in system — models were treating numbered「要求」as user「指令/乱码」。
        prepared.add(conversationService.buildUserMessage(PROACTIVE_USER_TRIGGER));

        ChatTurnResult chatResult = chatTurnFacade.invokeBlocking(ChatTurnCommand.builder()
                .scene(ChatTurnScene.PROACTIVE)
                .userId(userId)
                .conversationId(conversationId)
                .character(character)
                .provider(userVault.getProvider())
                .model(userVault.getModelDefault())
                .rawUserText(null)
                .modelUserText(null)
                .preparedMessages(prepared)
                .historyMessages(history)
                .extraSystemSuffix(buildProactiveTaskSystemSuffix(maxPieces))
                .streaming(false)
                .build());
        return self.persistProactiveReply(userId, conversationId, character, userVault, chatResult, maxPieces);
    }

    /** 仅最后一步「防重 + 落库 + 通知」进事务。 */
    @Transactional
    protected List<MessageResponse> persistProactiveReply(Long userId, Long conversationId, Character character,
                                                          VaultEntryResponse userVault, ChatTurnResult chatResult, int maxPieces) {
        List<MessageResponse> replies = conversationService.saveAssistantRepliesLimited(
                conversationId, character, chatResult.getContent(), chatResult.getTotalTokens(), maxPieces);
        if (!replies.isEmpty()) {
            proactiveUnrepliedThrottle.recordProactiveSent(conversationId);
            memoryWriter.enqueueSummary(conversationId, character.getId(), userId,
                    userVault.getProvider(), userVault.getModelDefault());
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
     * <p>
     * 故意不加 {@code @Transactional}：内部会调 AI / 下载语音，不能把外部调用
     * 包在数据库事务里——之前因此导致连接泄漏、锁超时、全站聊天卡死。
     * 真正需要事务的「防重 + 落库」只在最后一步小范围开启。
     */
    public void sendColdOpenFirstLine(Long userId, Long conversationId) {
        Conversation conversation = conversationService.findOwned(userId, conversationId);
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
        // 用户新建会话触发的破冰/meet：不受夜间免打扰拦截（DND 只挡主动推送）
        if (conversationService.isBlocked(character)) {
            log.debug("Cold open skipped (blocked): convId={}", conversationId);
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
            Message tail = conversationService.findLastMessage(conversationId);
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

            VaultEntryResponse userVault = conversationService.resolveUserTextVaultOrNull(userId);
            if (userVault == null) {
                log.debug("Cold open text skipped: no user text model, convId={}", conversationId);
                return;
            }

            CharacterChatBehavior behavior = chatBehaviorResolver.resolve(character);
            // Cold-open text: 1–2 short lines (not the full chat burst budget).
            int maxPieces = Math.min(2, Math.max(1, behavior.maxRepliesPerTurn()));
            String memoryContext = memoryRetriever.retrieveProfileContext(character.getId(), userId);
            String systemPrompt = proactiveSystemPrompt(userId, character, memoryContext, null);

            AiChatRequest aiRequest = new AiChatRequest();
            ChatToolContext.bindTo(aiRequest, character);
            aiRequest.setProvider(userVault.getProvider());
            aiRequest.setModel(userVault.getModelDefault());
            List<MessageDto> allMessages = new ArrayList<>();
            allMessages.add(conversationService.buildSystemMessage(systemPrompt + String.format("""


                    === 破冰任务（系统指令 · 不是用户发来的消息）===
                    这是你和该用户在本会话里的第一次开口（会话中还没有任何历史消息）。
                    请主动先发 1～%d 条很短的破冰话，符合口吻与设定，自然一点，避免机械客服腔。
                    若多条请用空行分隔；不要堆砌长段落。
                    禁止假装用户发了乱码、指令、报错或任何尚未出现的用户消息。
                    """, maxPieces)));
            allMessages.add(conversationService.buildUserMessage(PROACTIVE_USER_TRIGGER));
            aiRequest.setMessages(allMessages);

            ChatResult chatResult = aiChatService.chatBlocking(userId, aiRequest);
            if (conversationService.findLastMessage(conversationId) != null) {
                return;
            }
            // 仅最后一步「防重 + 落库」进事务，缩小锁持有窗口
            self.persistColdOpenReply(userId, conversationId, character, userVault, chatResult, maxPieces);
        } finally {
            redisTemplate.delete(lockKey);
        }
    }

    /**
     * 破冰话落库：小事务，只做「再确认空会话 + 插消息 + 入队记忆/通知」。
     */
    @Transactional
    protected void persistColdOpenReply(Long userId, Long conversationId, Character character,
                                        VaultEntryResponse userVault, ChatResult chatResult, int maxPieces) {
        if (conversationService.findLastMessage(conversationId) != null) {
            return;
        }
        List<MessageResponse> replies = conversationService.saveAssistantRepliesLimited(
                conversationId, character, chatResult.getContent(), chatResult.getTotalTokens(), maxPieces);
        if (!replies.isEmpty()) {
            memoryWriter.enqueueSummary(conversationId, character.getId(), userId,
                    userVault.getProvider(), userVault.getModelDefault());
            notificationService.notifyProactiveMessage(
                    userId,
                    conversationId,
                    character.getId(),
                    character.getName(),
                    replies.get(0).getContent());
            log.info("Cold open first line: convId={}, pieces={}", conversationId, replies.size());
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
        Conversation conversation = conversationService.findOwned(userId, conversationId);
        if (!"SINGLE".equalsIgnoreCase(conversation.getMode())) {
            return List.of();
        }
        Character character = characterMapper.selectById(conversation.getCharacterId());
        if (character == null) {
            return List.of();
        }
        // 用户主动打开聊天页：不受夜间免打扰拦截
        if (conversationService.isBlocked(character)) {
            return List.of();
        }
        Message last = conversationService.findLastMessage(conversationId);
        if (last == null) {
            // 空会话：若创建时因免打扰错过破冰/meet，打开时再补一次
            singleChatOpeningScheduler.startSequence(userId, conversationId);
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
        Conversation conversation = conversationService.findOwned(userId, conversationId);
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
            conversationService.ensureCharacterAvailableForProactive(character);
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
        Conversation conversation = conversationService.findOwned(userId, conversationId);
        if (!"SINGLE".equalsIgnoreCase(conversation.getMode())) {
            return List.of();
        }
        Character character = characterMapper.selectById(conversation.getCharacterId());
        if (character == null) {
            return List.of();
        }
        try {
            conversationService.ensureCharacterAvailableForProactive(character);
        } catch (BusinessException e) {
            return List.of();
        }
        if (proactiveUnrepliedThrottle.isPaused(conversationId)) {
            return List.of();
        }
        if (conversationService.findLastMessage(conversationId) == null) {
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
        if (conversationService.findLastMessage(conversationId) != null) {
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
        long seq = conversationService.getNextSeq(conversationId);
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
        return conversationService.toMessageResponse(assistantMsg);
    }

    /**
     * 若用户在「首条破冰」之后仍未回复（最后一条仍为助手侧），则再补一条简短关心；仅此一次，不会再发第三条。
     */
    // 故意不加 @Transactional：chatBlocking 会调 AI，长事务占住 Hikari 连接拖死前台。
    // 只在最后一步「落库 + 通知」进事务。
    public void sendColdOpenFollowUpIfStillSilent(Long userId, Long conversationId) {
        Conversation conversation = conversationService.findOwned(userId, conversationId);
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
            conversationService.ensureCharacterAvailableForProactive(character);
        } catch (BusinessException e) {
            return;
        }

        Message last = conversationService.findLastMessage(conversationId);
        if (last == null) {
            return;
        }
        if ("USER".equalsIgnoreCase(last.getRole())) {
            return;
        }

        VaultEntryResponse userVault = conversationService.resolveUserTextVaultOrNull(userId);
        if (userVault == null) {
            log.debug("Cold open follow-up skipped: no user text model, convId={}", conversationId);
            return;
        }

        String memoryContext = memoryRetriever.retrieveProfileContext(character.getId(), userId);
        String systemPrompt = proactiveSystemPrompt(userId, character, memoryContext, null);
        List<Message> history = conversationService.getRecentMessages(conversationId, contextWindow);

        AiChatRequest aiRequest = new AiChatRequest();
        ChatToolContext.bindTo(aiRequest, character);
        aiRequest.setProvider(userVault.getProvider());
        aiRequest.setModel(userVault.getModelDefault());
        List<MessageDto> allMessages = new ArrayList<>();
        allMessages.add(conversationService.buildSystemMessage(systemPrompt + """


                === 破冰跟进（系统指令 · 不是用户发来的消息）===
                用户在你上一条之后仍未回复。请再发**唯一一条**非常简短的关心或轻轻一推（一两句话即可）。
                不要重复上一句的意思，不要过于啰嗦。
                这是本条场景下系统允许你的**最后一次自动开口**；发完后安静等用户就好。
                禁止假装用户刚发了乱码、指令、报错或任何未在历史中真实出现的用户消息。
                """));
        for (Message msg : history) {
            MessageDto dto = new MessageDto();
            dto.setRole(msg.getRole().toLowerCase());
            dto.setContent(msg.getContent());
            allMessages.add(dto);
        }
        allMessages.add(conversationService.buildUserMessage(PROACTIVE_USER_TRIGGER));

        aiRequest.setMessages(allMessages);
        aiRequest.setBackground(true);
        ChatResult chatResult = aiChatService.chatBlocking(userId, aiRequest);
        self.persistColdOpenFollowUpReply(userId, conversationId, character, userVault, chatResult);
    }

    /** 仅最后一步「落库 + 通知」进事务。 */
    @Transactional
    protected void persistColdOpenFollowUpReply(Long userId, Long conversationId, Character character,
                                                VaultEntryResponse userVault, ChatResult chatResult) {
        List<MessageResponse> replies = conversationService.saveAssistantRepliesLimited(
                conversationId, character, chatResult.getContent(), chatResult.getTotalTokens(), 1);
        if (!replies.isEmpty()) {
            memoryWriter.enqueueSummary(conversationId, character.getId(), userId,
                    userVault.getProvider(), userVault.getModelDefault());
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
     * 故意不加 @Transactional：chatBlocking 会调 AI，长事务占住 Hikari 连接拖死前台。
     * 只在最后一步「落库 + 通知」进事务。
     */
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
            conversationService.ensureCharacterAvailableForProactive(character);
        } catch (BusinessException e) {
            log.debug("City change follow-up skipped: convId={}, reason={}", conversationId, e.getMessage());
            return;
        }
        VaultEntryResponse userVault = conversationService.resolveUserTextVaultOrNull(userId);
        if (userVault == null) {
            log.debug("City change follow-up skipped: no user text model, convId={}", conversationId);
            return;
        }

        String memoryContext = memoryRetriever.retrieveProfileContext(character.getId(), userId);
        String relationshipContext = relationshipStateService.buildPromptContext(userId, character.getId());
        String mergedMemory = memoryContext == null ? relationshipContext
                : memoryContext + "\n\n" + relationshipContext;
        String addressing = resolveUserAddressing(userId, memoryContext);

        String systemPrompt = proactiveSystemPrompt(userId, character, mergedMemory, null);
        systemPrompt = appendCityChangeContext(systemPrompt, previousCity, newCity);

        List<Message> history = conversationService.getRecentMessages(conversationId, contextWindow);
        AiChatRequest aiRequest = new AiChatRequest();
        ChatToolContext.bindTo(aiRequest, character);
        aiRequest.setProvider(userVault.getProvider());
        aiRequest.setModel(userVault.getModelDefault());
        List<MessageDto> allMessages = new ArrayList<>();
        allMessages.add(conversationService.buildSystemMessage(systemPrompt + String.format("""


                === 城市变更关心（系统指令 · 不是用户发来的消息）===
                系统检测到用户刚刚把自己的现实所在城市从「%s」改成了「%s」。
                请主动发一条关心用户的消息，核心要问用户是不是搬家/换城市了、发生什么事了。
                必须用称呼「%s」；必须明确提到从「%s」到「%s」的变化。
                参考句式（可略作口语化，但不要改城市名、不要否认搬迁）：%s，我看你从%s来到了%s，是发生了什么事情吗？
                只发 1 条，不要太长；不要重复历史原话。
                禁止假装用户发了乱码、指令或报错。
                """, previousCity, newCity, addressing, previousCity, newCity,
                addressing, previousCity, newCity)));
        for (Message msg : history) {
            MessageDto dto = new MessageDto();
            dto.setRole(msg.getRole().toLowerCase());
            dto.setContent(msg.getContent());
            allMessages.add(dto);
        }
        allMessages.add(conversationService.buildUserMessage(PROACTIVE_USER_TRIGGER));
        aiRequest.setMessages(allMessages);
        aiRequest.setBackground(true);

        ChatResult chatResult = aiChatService.chatBlocking(userId, aiRequest);
        self.persistCityChangeFollowUpReply(userId, conversationId, character, userVault, chatResult, previousCity, newCity);
    }

    /** 仅最后一步「落库 + 通知」进事务。 */
    @Transactional
    protected void persistCityChangeFollowUpReply(Long userId, Long conversationId, Character character,
                                                  VaultEntryResponse userVault, ChatResult chatResult,
                                                  String previousCity, String newCity) {
        List<MessageResponse> replies = conversationService.saveAssistantRepliesLimited(
                conversationId, character, chatResult.getContent(), chatResult.getTotalTokens(), 1);
        if (!replies.isEmpty()) {
            memoryWriter.enqueueSummary(conversationId, character.getId(), userId,
                    userVault.getProvider(), userVault.getModelDefault());
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

}
