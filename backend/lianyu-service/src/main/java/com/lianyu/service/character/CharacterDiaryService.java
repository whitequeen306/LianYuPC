package com.lianyu.service.character;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lianyu.common.base.ErrorCode;
import com.lianyu.common.exception.BusinessException;
import com.lianyu.common.i18n.OutputLanguage;
import com.lianyu.dao.dto.CharacterDiaryLastDateRow;
import com.lianyu.dao.dto.ConversationActivityRow;
import com.lianyu.dao.entity.Character;
import com.lianyu.dao.entity.CharacterDiary;
import com.lianyu.dao.entity.Conversation;
import com.lianyu.dao.entity.Message;
import com.lianyu.dao.mapper.CharacterDiaryMapper;
import com.lianyu.dao.mapper.CharacterMapper;
import com.lianyu.dao.mapper.ConversationMapper;
import com.lianyu.dao.mapper.MessageMapper;
import com.lianyu.service.ai.ApiKeyVaultService;
import com.lianyu.service.ai.background.AiBackgroundPublisher;
import com.lianyu.service.ai.background.AiBackgroundTask;
import com.lianyu.service.dto.MessageDto;
import com.lianyu.service.dto.VaultEntryResponse;
import com.lianyu.service.relationship.RelationshipStateService;
import com.lianyu.service.notification.NotificationService;
import com.lianyu.service.support.OutputLanguageService;
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 角色日记服务。
 *
 * <p>触发算法（2026-10 重构，解决「聊很久看不到日记」）：
 * <ul>
 *   <li>候选 = 当天有过用户消息的 SINGLE 会话（按当天用户消息数降序），不再按会话创建时间扫描；</li>
 *   <li><b>两天保底</b>：距该角色最后一篇日记（任何来源）≥ {@code min-days-between-diaries} 天
 *       且当天聊过 → 必写；2 天内写过则按当天聊天量分档概率（light / moderate）；</li>
 *   <li>上限：每轮全局 {@code max-per-run} + 每用户 {@code max-per-user-per-run}；</li>
 *   <li>每天 22:00 主扫 + 23:30 补扫（抓 22 点后才聊天的会话），幂等由来源内「每天一篇」去重保证；</li>
 *   <li>来源互斥各自独立：定时（SCHEDULED）只数 SCHEDULED，即兴（IMPROMPTU，角色聊天中调
 *       write_diary 工具）只数 IMPROMPTU；保底按任何来源的最后一篇计算；</li>
 *   <li>素材：当天消息优先（不足则回填最近历史），日记必须引用当天聊过的具体细节。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CharacterDiaryService {

    /** 未达保底时，当天用户消息数达到该值即用 moderate 档概率（否则 light 档）。 */
    static final int MODERATE_TIER_MIN_MESSAGES = 3;
    /** 素材：当天消息最多取多少条。 */
    static final int MATERIAL_TODAY_LIMIT = 40;
    /** 素材：当天消息少于该条数时，回填最近历史消息作上下文。 */
    static final int MATERIAL_BACKFILL_THRESHOLD = 10;
    /** 素材：回填的历史消息最多多少条。 */
    static final int MATERIAL_BACKFILL_LIMIT = 20;

    private final CharacterDiaryMapper diaryMapper;
    private final CharacterMapper characterMapper;
    private final ConversationMapper conversationMapper;
    private final MessageMapper messageMapper;
    private final ApiKeyVaultService apiKeyVaultService;
    private final com.lianyu.service.graph.ChatTurnFacade chatTurnFacade;

    /** Optional: AGENT mode delegates the model call to backend-agent (null => fallback to Java). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.lianyu.service.agent.AgentTextPort agentTextPort;

    private final OutputLanguageService outputLanguageService;
    private final ObjectMapper objectMapper;
    private final RelationshipStateService relationshipStateService;
    private final NotificationService notificationService;
    private final CharacterRecentActivityService characterRecentActivityService;
    private final AiBackgroundPublisher aiBackgroundPublisher;

    @Value("${lianyu.diary.enabled:true}")
    private boolean diaryEnabled;

    /** 每轮扫描当天最活跃的会话数。 */
    @Value("${lianyu.diary.scan-limit:30}")
    private int scanLimit;

    /** 距最后一篇日记（任何来源）≥ 该天数且当天聊过 → 保底必写。 */
    @Value("${lianyu.diary.min-days-between-diaries:2}")
    private int minDaysBetweenDiaries;

    /** 未达保底、当天 1~2 条用户消息时的尝试概率。 */
    @Value("${lianyu.diary.probability-light:0.3}")
    private double probabilityLight;

    /** 未达保底、当天 ≥3 条用户消息时的尝试概率。 */
    @Value("${lianyu.diary.probability-moderate:0.5}")
    private double probabilityModerate;

    /** 每轮全局生成上限（费用走用户各自 vault）。 */
    @Value("${lianyu.diary.max-per-run:20}")
    private int maxPerRun;

    /** 每轮单用户上限（多角色用户一晚最多几篇）。 */
    @Value("${lianyu.diary.max-per-user-per-run:2}")
    private int maxPerUserPerRun;

    /** 即兴日记（write_diary 工具）每角色每天最多篇数。 */
    @Value("${lianyu.diary.impromptu-max-per-day:1}")
    private int impromptuMaxPerDay;

    @Value("${lianyu.diary.content-max-chars:900}")
    private int contentMaxChars;

    /** 主扫：每天 22:00。 */
    @Scheduled(cron = "0 0 22 * * *")
    public void scheduledGenerate() {
        runScheduledGenerate();
    }

    /** 补扫：每天 23:30，抓 22 点后才聊天的会话；幂等，已写成的角色自然跳过。 */
    @Scheduled(cron = "0 30 23 * * *")
    public void scheduledGenerateLate() {
        runScheduledGenerate();
    }

    private void runScheduledGenerate() {
        if (!diaryEnabled) {
            return;
        }
        log.info("Diary generation cron triggered");
        try {
            int count = generateDiaries();
            log.info("Diary generation completed: {} diaries enqueued", count);
        } catch (Exception e) {
            log.error("Diary generation failed", e);
        }
    }

    /**
     * 扫描当天活跃会话并入队日记生成任务。
     * 保底候选（距最后一篇日记 ≥ min-days-between-diaries 天）优先拿名额，
     * 其余按当天用户消息数降序；受全局与单用户双上限约束。
     */
    public int generateDiaries() {
        LocalDate today = LocalDate.now();
        LocalDateTime startOfDay = today.atStartOfDay();
        List<ConversationActivityRow> active = conversationMapper.selectSingleConversationsActiveSince(
                startOfDay, Math.max(1, scanLimit));
        if (active.isEmpty()) {
            return 0;
        }

        Set<Long> candidateCharacterIds = new LinkedHashSet<>();
        Map<Long, Long> charToUser = new HashMap<>();
        Map<Long, Long> charToUserMsgs = new HashMap<>();
        for (ConversationActivityRow row : active) {
            if (row.getCharacterId() == null || row.getUserId() == null) {
                continue;
            }
            candidateCharacterIds.add(row.getCharacterId());
            charToUser.putIfAbsent(row.getCharacterId(), row.getUserId());
            charToUserMsgs.putIfAbsent(row.getCharacterId(),
                    row.getUserMsgCount() == null ? 0L : row.getUserMsgCount());
        }
        if (candidateCharacterIds.isEmpty()) {
            return 0;
        }

        // 今天已有定时日记的角色跳过（即兴日记不占定时互斥）
        Set<Long> scheduledToday = new HashSet<>();
        List<CharacterDiary> todayDiaries = diaryMapper.selectList(
                new LambdaQueryWrapper<CharacterDiary>()
                        .ge(CharacterDiary::getCreatedAt, startOfDay)
                        .in(CharacterDiary::getCharacterId, candidateCharacterIds));
        for (CharacterDiary d : todayDiaries) {
            if (!CharacterDiary.SOURCE_IMPROMPTU.equals(d.getSource())) {
                scheduledToday.add(d.getCharacterId());
            }
        }
        candidateCharacterIds.removeAll(scheduledToday);
        if (candidateCharacterIds.isEmpty()) {
            return 0;
        }

        // 每角色最后一篇日记（任何来源）→ 两天保底判定
        Map<Long, LocalDate> lastDiaryDates = new HashMap<>();
        for (CharacterDiaryLastDateRow r : diaryMapper.selectLastDiaryDates(
                new ArrayList<>(candidateCharacterIds))) {
            if (r != null && r.getCharacterId() != null && r.getLastDiaryAt() != null) {
                lastDiaryDates.put(r.getCharacterId(), r.getLastDiaryAt().toLocalDate());
            }
        }

        // 保底候选优先，其后按当天用户消息数降序
        List<Long> ordered = new ArrayList<>(candidateCharacterIds);
        ordered.sort(Comparator
                .comparing((Long cid) -> isForced(lastDiaryDates.get(cid), today) ? 0 : 1)
                .thenComparing(cid -> charToUserMsgs.getOrDefault(cid, 0L), Comparator.reverseOrder()));

        Map<Long, Integer> perUserCount = new HashMap<>();
        int created = 0;
        for (Long characterId : ordered) {
            if (created >= Math.max(1, maxPerRun)) {
                break;
            }
            Long userId = charToUser.get(characterId);
            if (userId == null) {
                continue;
            }
            if (perUserCount.getOrDefault(userId, 0) >= Math.max(1, maxPerUserPerRun)) {
                continue;
            }
            double p = resolveAttemptProbability(charToUserMsgs.getOrDefault(characterId, 0L),
                    lastDiaryDates.get(characterId), today,
                    Math.max(0, minDaysBetweenDiaries), probabilityLight, probabilityModerate);
            if (!roll(p)) {
                continue;
            }
            try {
                aiBackgroundPublisher.publish(AiBackgroundTask.characterDiary(userId, characterId));
                perUserCount.merge(userId, 1, Integer::sum);
                created++;
            } catch (Exception e) {
                log.debug("Diary enqueue skipped for charId={}, reason={}", characterId, e.getMessage());
            }
        }
        return created;
    }

    /** MQ 消费：生成一篇角色日记（source 为 null 视为定时）。 */
    public void processDiaryJob(AiBackgroundTask task) {
        if (task == null || task.userId() == null || task.characterId() == null) {
            return;
        }
        CharacterDiary diary = tryGenerateDiary(task.userId(), task.characterId(), task.source());
        if (diary != null) {
            log.info("Diary created: charId={}, source={}, title={}",
                    task.characterId(), diary.getSource(), diary.getTitle());
        }
    }

    // 故意不加 @Transactional：chatBlocking 会调 AI，长事务占住 Hikari 连接拖死前台。
    // 只在最后一步「落库 + 通知」进事务。
    public CharacterDiary tryGenerateDiary(Long userId, Long characterId, String rawSource) {
        String source = CharacterDiary.SOURCE_IMPROMPTU.equals(rawSource)
                ? CharacterDiary.SOURCE_IMPROMPTU
                : CharacterDiary.SOURCE_SCHEDULED;
        Character character = characterMapper.selectById(characterId);
        if (character == null) {
            return null;
        }

        LocalDate today = LocalDate.now();
        LocalDateTime startOfDay = today.atStartOfDay();

        // 来源内「每天一篇」互斥：定时只数 SCHEDULED（即兴不占），即兴只数 IMPROMPTU（受配额限制）
        int maxForSource = CharacterDiary.SOURCE_IMPROMPTU.equals(source)
                ? Math.max(1, impromptuMaxPerDay)
                : 1;
        Long count = diaryMapper.selectCount(new LambdaQueryWrapper<CharacterDiary>()
                .eq(CharacterDiary::getCharacterId, characterId)
                .eq(CharacterDiary::getUserId, userId)
                .eq(CharacterDiary::getSource, source)
                .ge(CharacterDiary::getCreatedAt, startOfDay));
        if (count != null && count >= maxForSource) {
            return null;
        }

        Conversation conversation = conversationMapper.selectOne(
                new LambdaQueryWrapper<Conversation>()
                        .eq(Conversation::getUserId, userId)
                        .eq(Conversation::getCharacterId, characterId)
                        .eq(Conversation::getMode, "SINGLE")
                        .orderByDesc(Conversation::getCreatedAt)
                        .last("LIMIT 1"));
        if (conversation == null) {
            return null;
        }

        // 硬门槛：今天没聊过就不写——日记必须基于当天的交流
        Long todayMsgCount = messageMapper.selectCount(new LambdaQueryWrapper<Message>()
                .eq(Message::getConversationId, conversation.getId())
                .ge(Message::getCreatedAt, startOfDay));
        if (todayMsgCount == null || todayMsgCount == 0) {
            log.debug("Diary skipped: no messages today, conv={}", conversation.getId());
            return null;
        }

        // 素材：今天的消息优先，不足时回填最近历史作上下文
        List<Message> todayMessages = messageMapper.selectList(
                new LambdaQueryWrapper<Message>()
                        .eq(Message::getConversationId, conversation.getId())
                        .ge(Message::getCreatedAt, startOfDay)
                        .orderByAsc(Message::getSeq)
                        .last("LIMIT " + MATERIAL_TODAY_LIMIT));
        List<Message> backfillMessages = todayMessages.size() < MATERIAL_BACKFILL_THRESHOLD
                ? messageMapper.selectList(new LambdaQueryWrapper<Message>()
                        .eq(Message::getConversationId, conversation.getId())
                        .lt(Message::getCreatedAt, startOfDay)
                        .orderByDesc(Message::getSeq)
                        .last("LIMIT " + MATERIAL_BACKFILL_LIMIT))
                : List.of();
        Collections.reverse(backfillMessages);

        String lang = outputLanguageService.resolveForUser(userId);
        String basePrompt = chatTurnFacade.assembleSystemPrompt(
                com.lianyu.ai.graph.ChatTurnScene.DIARY,
                userId,
                null,
                character,
                null,
                null,
                null,
                null);

        String instruction = switch (OutputLanguage.fromCode(lang)) {
            case JA -> """
                    あなたは今、一日の終わりに日記を書いています。これはユーザーに見せるための公開テキストではなく、
                    あなた自身の本当の気持ちを綴る「心の日記」です。朋友圈のような短い一言ではなく、
                    長めの内省・描写・余韻を書いてください。

                    最近の会話を振り返り、今日感じたこと、相手への想い、
                    嬉しかったことや少し寂しかったことを、あなたの性格と話し方で自然に書いてください。
                    日記には今日話した具体的な話題や細部を必ず織り込んでください。漠然とした気分だけでは書かないでください。

                    出力形式（JSONのみ）:
                    {"title":"日記のタイトル（10字以内）","content":"日記本文（400〜800字、段落可、朋友圈よりずっと長く）"}
                    """;
            case EN -> """
                    You are writing in your diary at the end of the day. This is NOT a public post—
                    it's your private inner thoughts, your true feelings. Write much longer than a social feed update:
                    layered feelings, small details, and inner monologue are welcome.

                    Reflect on today's conversations: what you felt, what touched you,
                    what made you happy or a little sad. Write naturally in your own voice.
                    You MUST weave in concrete topics and details from today's conversation; do not write vague moods only.

                    Output format (JSON only):
                    {"title":"Diary title (max 10 words)","content":"Diary content (400-800 chars, multi-sentence, longer than moments)"}
                    """;
            case ZH_TW -> """
                    你正在一天的尾聲寫日記。這不是給用戶看的公開動態——而是你自己真實的內心獨白。
                    要比朋友圈長很多：可以分段、寫細節、寫情緒的起伏，不要寫成一句話短動態。

                    回顧今天的對話，你感受到什麼、什麼觸動了你、
                    讓你開心或有點失落的事。用你自己的語氣自然地寫。
                    日記必須提到今天聊過的具體話題或細節，不要寫成泛泛的心情。

                    輸出格式（僅JSON）：
                    {"title":"日記標題（10字以內）","content":"日記內文（400〜800字，自然文體，明顯長於朋友圈）"}
                    """;
            default -> """
                    你正在一天的尾声写日记。这不是给用户看的公开动态——而是你自己真实的内心独白。
                    要比朋友圈长很多：可以分段、写细节、写情绪的起伏，不要写成一句话短动态。

                    回顾今天的对话，你感受到什么、什么触动了你、
                    让你开心或有点失落的事。用你自己的语气自然地写。
                    日记必须提到今天聊过的具体话题或细节，不要写成泛泛的心情。

                    输出格式（仅JSON）：
                    {"title":"日记标题（10字以内）","content":"日记正文（400~800字，自然文体，明显长于朋友圈）"}
                    """;
        };

        String chatSummary = buildDiaryChatMaterial(backfillMessages, todayMessages);

        VaultEntryResponse userVault = apiKeyVaultService.resolvePreferredUserVault(userId);
        if (userVault == null) {
            log.debug("Diary generation skipped: no user text model, userId={}, characterId={}",
                    userId, character.getId());
            return null;
        }

        try {
            if (agentTextPort == null) {
                throw new BusinessException(ErrorCode.AI_PROVIDER_ERROR, "日记生成服务不可用");
            }
            String content = agentTextPort.complete(
                    userId, basePrompt, instruction + "\n\n" + chatSummary,
                    userVault.getProvider(), userVault.getModelDefault(), 0.85);
            if (content == null || content.isBlank()) {
                throw new BusinessException(ErrorCode.AI_PROVIDER_ERROR, "日记生成失败，请稍后再试");
            }

            String json = extractJsonObject(content.trim());
            JsonNode root = objectMapper.readTree(json);

            String title = root.has("title") ? root.get("title").asText().trim() : character.getName() + "的日记";
            String diaryContent = root.has("content") ? root.get("content").asText().trim() : content.trim();

            if (diaryContent.length() > contentMaxChars) {
                diaryContent = diaryContent.substring(0, contentMaxChars);
            }
            if (diaryContent.length() < 80) {
                return null; // 太短不像日记
            }

            return persistDiary(userId, characterId, character, conversation, title, diaryContent, source);
        } catch (Exception e) {
            log.debug("Diary generation failed for charId={}, reason={}", characterId, e.getMessage());
            return null;
        }
    }

    /**
     * 触发档位概率（纯函数，便于单测）：
     * <ul>
     *   <li>距最后一篇日记（任何来源）≥ {@code minDaysBetweenDiaries} 天（或从未写过）→ 保底 1.0；</li>
     *   <li>否则按当天用户消息数分档：≥ {@link #MODERATE_TIER_MIN_MESSAGES} 条用 moderate，否则 light。</li>
     * </ul>
     */
    static double resolveAttemptProbability(long userMsgsToday, LocalDate lastDiaryDate, LocalDate today,
                                            int minDaysBetweenDiaries,
                                            double probabilityLight, double probabilityModerate) {
        if (lastDiaryDate == null
                || ChronoUnit.DAYS.between(lastDiaryDate, today) >= Math.max(0, minDaysBetweenDiaries)) {
            return 1.0;
        }
        return userMsgsToday >= MODERATE_TIER_MIN_MESSAGES ? probabilityModerate : probabilityLight;
    }

    private boolean isForced(LocalDate lastDiaryDate, LocalDate today) {
        return lastDiaryDate == null
                || ChronoUnit.DAYS.between(lastDiaryDate, today) >= Math.max(0, minDaysBetweenDiaries);
    }

    private boolean roll(double probability) {
        if (probability <= 0) {
            return false;
        }
        if (probability >= 1) {
            return true;
        }
        return ThreadLocalRandom.current().nextDouble() < probability;
    }

    /**
     * 对话素材摘要（纯函数，便于单测）：回填的历史消息在前作上下文，今天的消息为主体。
     * 每条截 120 字，按 对方/我 标注角色。
     */
    static String buildDiaryChatMaterial(List<Message> backfillMessages, List<Message> todayMessages) {
        StringBuilder sb = new StringBuilder();
        if (backfillMessages != null && !backfillMessages.isEmpty()) {
            sb.append("此前对话片段：\n");
            appendMessages(sb, backfillMessages);
        }
        sb.append("今天（").append(LocalDate.now()).append("）的对话：\n");
        appendMessages(sb, todayMessages == null ? List.of() : todayMessages);
        return sb.toString();
    }

    private static void appendMessages(StringBuilder sb, List<Message> messages) {
        for (Message msg : messages) {
            if (msg.getContent() == null || msg.getContent().isBlank()) {
                continue;
            }
            String roleLabel = "USER".equalsIgnoreCase(msg.getRole()) ? "对方" : "我";
            String trimmed = msg.getContent().length() > 120
                    ? msg.getContent().substring(0, 120) + "…"
                    : msg.getContent();
            sb.append(roleLabel).append(": ").append(trimmed).append("\n");
        }
    }

    /** 仅最后一步「落库 + 通知」进事务。 */
    @Transactional
    protected CharacterDiary persistDiary(Long userId, Long characterId, Character character,
                                          Conversation conversation, String title, String diaryContent,
                                          String source) {
        CharacterDiary diary = new CharacterDiary();
        diary.setCharacterId(characterId);
        diary.setUserId(userId);
        diary.setTitle(title.length() > 100 ? title.substring(0, 100) : title);
        diary.setContent(diaryContent);
        diary.setMood(null);
        diary.setConversationId(conversation.getId());
        diary.setSource(source);
        diaryMapper.insert(diary);
        characterRecentActivityService.evictCache(userId, characterId);

        String preview = title;
        if (preview == null || preview.isBlank()) {
            preview = diaryContent;
        }
        notificationService.notifyDiaryNew(
                userId,
                conversation.getId(),
                characterId,
                character.getName(),
                preview
        );
        return diary;
    }

    /**
     * 分页获取日记列表。
     */
    public List<CharacterDiary> listDiaries(Long userId, Long characterId, int page, int size) {
        int safeSize = Math.min(50, Math.max(1, size));
        int offset = (Math.max(1, page) - 1) * safeSize;

        LambdaQueryWrapper<CharacterDiary> q = new LambdaQueryWrapper<CharacterDiary>()
                .eq(CharacterDiary::getUserId, userId)
                .orderByDesc(CharacterDiary::getCreatedAt);
        if (characterId != null) {
            q.eq(CharacterDiary::getCharacterId, characterId);
        }
        q.last("LIMIT " + safeSize + " OFFSET " + offset);

        return diaryMapper.selectList(q);
    }

    /**
     * 获取单篇日记。
     */
    public CharacterDiary getDiary(Long userId, Long diaryId) {
        return diaryMapper.selectOne(new LambdaQueryWrapper<CharacterDiary>()
                .eq(CharacterDiary::getId, diaryId)
                .eq(CharacterDiary::getUserId, userId));
    }

    private String extractJsonObject(String text) {
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return text;
    }
}
