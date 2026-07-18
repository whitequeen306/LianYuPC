package com.lianyu.service.character;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lianyu.common.constant.AiConstants;
import com.lianyu.common.i18n.OutputLanguage;
import com.lianyu.dao.entity.Character;
import com.lianyu.dao.entity.CharacterDiary;
import com.lianyu.dao.entity.Conversation;
import com.lianyu.dao.entity.Message;
import com.lianyu.dao.mapper.CharacterDiaryMapper;
import com.lianyu.dao.mapper.CharacterMapper;
import com.lianyu.dao.mapper.ConversationMapper;
import com.lianyu.dao.mapper.MessageMapper;
import com.lianyu.service.ai.AiChatService;
import com.lianyu.service.ai.CharacterPromptBuilder;
import com.lianyu.service.dto.AiChatRequest;
import com.lianyu.service.dto.ChatResult;
import com.lianyu.service.dto.MessageDto;
import com.lianyu.service.memory.MemoryRetriever;
import com.lianyu.service.relationship.RelationshipStateService;
import com.lianyu.service.notification.NotificationService;
import com.lianyu.service.support.OutputLanguageService;
import com.lianyu.service.tools.ChatToolContext;
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;



/**
 * 角色日记服务。
 * <ul>
 *   <li>每天定时为有活跃对话的角色生成一篇内心独白日记</li>
 *   <li>每个角色每天最多 1 篇</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CharacterDiaryService {

    private final CharacterDiaryMapper diaryMapper;
    private final CharacterMapper characterMapper;
    private final ConversationMapper conversationMapper;
    private final MessageMapper messageMapper;
    private final AiChatService aiChatService;
    private final com.lianyu.service.graph.ChatTurnFacade chatTurnFacade;
    private final CharacterPromptBuilder promptBuilder;
    private final MemoryRetriever memoryRetriever;
    private final OutputLanguageService outputLanguageService;
    private final ObjectMapper objectMapper;
    private final RelationshipStateService relationshipStateService;
    private final NotificationService notificationService;
    private final CharacterRecentActivityService characterRecentActivityService;

    @Value("${lianyu.diary.enabled:true}")
    private boolean diaryEnabled;

    @Value("${lianyu.diary.max-per-run:3}")
    private int maxPerRun;

    @Value("${lianyu.diary.scan-limit:30}")
    private int scanLimit;

    @Value("${lianyu.diary.content-max-chars:500}")
    private int contentMaxChars;

    /** 每个候选角色尝试生成日记的概率（0~1），默认 60% */
    @Value("${lianyu.diary.trigger-probability:0.6}")
    private double diaryTriggerProbability;

    /**
     * 定时任务：每天 22:00 执行一次，为当天活跃对话的角色生成日记。
     */
    @Scheduled(cron = "0 0 22 * * *")
    public void scheduledGenerate() {
        if (!diaryEnabled) {
            return;
        }
        log.info("Diary generation cron triggered");
        try {
            int count = generateDiaries();
            log.info("Diary generation completed: {} diaries written", count);
        } catch (Exception e) {
            log.error("Diary generation failed", e);
        }
    }

    /**
     * 手动触发一批日记生成。
     */
    public int generateDiaries() {
        // 找出最近活跃的 SINGLE 会话
        List<Conversation> conversations = conversationMapper.selectList(
                new LambdaQueryWrapper<Conversation>()
                        .eq(Conversation::getMode, "SINGLE")
                        .isNotNull(Conversation::getCharacterId)
                        .orderByDesc(Conversation::getCreatedAt)
                        .last("LIMIT " + Math.max(1, scanLimit)));

        if (conversations.isEmpty()) {
            return 0;
        }

        // 收集所有角色
        Set<Long> characterIds = new LinkedHashSet<>();
        Map<Long, Long> charToUser = new HashMap<>();
        for (Conversation conv : conversations) {
            if (conv.getCharacterId() != null) {
                characterIds.add(conv.getCharacterId());
                charToUser.putIfAbsent(conv.getCharacterId(), conv.getUserId());
            }
        }

        if (characterIds.isEmpty()) {
            return 0;
        }

        // 检查今天已生成过日记的角色
        LocalDateTime startOfDay = LocalDate.now().atStartOfDay();
        Set<Long> alreadyGenerated = new HashSet<>();
        List<CharacterDiary> todayDiaries = diaryMapper.selectList(
                new LambdaQueryWrapper<CharacterDiary>()
                        .ge(CharacterDiary::getCreatedAt, startOfDay)
                        .in(CharacterDiary::getCharacterId, characterIds));
        for (CharacterDiary d : todayDiaries) {
            alreadyGenerated.add(d.getCharacterId());
        }

        // 洗牌随机性
        List<Long> candidates = new ArrayList<>(characterIds);
        Collections.shuffle(candidates);

        int created = 0;
        for (Long characterId : candidates) {
            if (created >= Math.max(1, maxPerRun)) {
                break;
            }
            if (alreadyGenerated.contains(characterId)) {
                continue;
            }
            Long userId = charToUser.get(characterId);
            if (userId == null) {
                continue;
            }
            // 概率触发：不是每个角色每天都写日记（默认 60% 尝试生成）
            if (!shouldAttemptDiary()) {
                continue;
            }
            try {
                CharacterDiary diary = tryGenerateDiary(userId, characterId);
                if (diary != null) {
                    created++;
                    log.info("Diary created: charId={}, title={}", characterId, diary.getTitle());
                }
            } catch (Exception e) {
                log.debug("Diary generation skipped for charId={}, reason={}", characterId, e.getMessage());
            }
        }
        return created;
    }

    @Transactional
    public CharacterDiary tryGenerateDiary(Long userId, Long characterId) {
        Character character = characterMapper.selectById(characterId);
        if (character == null) {
            return null;
        }

        // 检查今天是否已有日记
        LocalDateTime startOfDay = LocalDate.now().atStartOfDay();
        Long count = diaryMapper.selectCount(new LambdaQueryWrapper<CharacterDiary>()
                .eq(CharacterDiary::getCharacterId, characterId)
                .eq(CharacterDiary::getUserId, userId)
                .ge(CharacterDiary::getCreatedAt, startOfDay));
        if (count != null && count > 0) {
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

        List<Message> recentMessages = messageMapper.selectList(
                new LambdaQueryWrapper<Message>()
                        .eq(Message::getConversationId, conversation.getId())
                        .orderByDesc(Message::getSeq)
                        .last("LIMIT 20"));
        if (recentMessages.isEmpty()) {
            return null;
        }
        Collections.reverse(recentMessages);

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

                    出力形式（JSONのみ）:
                    {"title":"日記のタイトル（10字以内）","content":"日記本文（400〜800字、段落可、朋友圈よりずっと長く）"}
                    """;
            case EN -> """
                    You are writing in your diary at the end of the day. This is NOT a public post—
                    it's your private inner thoughts, your true feelings. Write much longer than a social feed update:
                    layered feelings, small details, and inner monologue are welcome.

                    Reflect on today's conversations: what you felt, what touched you,
                    what made you happy or a little sad. Write naturally in your own voice.

                    Output format (JSON only):
                    {"title":"Diary title (max 10 words)","content":"Diary content (400-800 chars, multi-sentence, longer than moments)"}
                    """;
            case ZH_TW -> """
                    你正在一天的尾聲寫日記。這不是給用戶看的公開動態——而是你自己真實的內心獨白。
                    要比朋友圈長很多：可以分段、寫細節、寫情緒的起伏，不要寫成一句話短動態。

                    回顧今天的對話，你感受到什麼、什麼觸動了你、
                    讓你開心或有點失落的事。用你自己的語氣自然地寫。

                    輸出格式（僅JSON）：
                    {"title":"日記標題（10字以內）","content":"日記內文（400〜800字，自然文體，明顯長於朋友圈）"}
                    """;
            default -> """
                    你正在一天的尾声写日记。这不是给用户看的公开动态——而是你自己真实的内心独白。
                    要比朋友圈长很多：可以分段、写细节、写情绪的起伏，不要写成一句话短动态。

                    回顾今天的对话，你感受到什么、什么触动了你、
                    让你开心或有点失落的事。用你自己的语气自然地写。

                    输出格式（仅JSON）：
                    {"title":"日记标题（10字以内）","content":"日记正文（400~800字，自然文体，明显长于朋友圈）"}
                    """;
        };

        // 构建最近的对话摘要
        StringBuilder chatSummary = new StringBuilder();
        chatSummary.append("今日对话片段：\n");
        for (Message msg : recentMessages) {
            if (msg.getContent() == null || msg.getContent().isBlank()) {
                continue;
            }
            String roleLabel = "USER".equalsIgnoreCase(msg.getRole()) ? "对方" : "我";
            String trimmed = msg.getContent().length() > 120
                    ? msg.getContent().substring(0, 120) + "…"
                    : msg.getContent();
            chatSummary.append(roleLabel).append(": ").append(trimmed).append("\n");
        }

        AiChatRequest aiReq = new AiChatRequest();
        aiReq.setProvider(AiConstants.PLATFORM_PROVIDER);
        aiReq.setTemperature(0.85);
        ChatToolContext.bindTo(aiReq, character);

        List<MessageDto> messages = new ArrayList<>();
        MessageDto sys = new MessageDto();
        sys.setRole("system");
        sys.setContent(basePrompt);
        messages.add(sys);
        MessageDto user = new MessageDto();
        user.setRole("user");
        user.setContent(instruction + "\n\n" + chatSummary);
        messages.add(user);
        aiReq.setMessages(messages);

        try {
            ChatResult result = aiChatService.chatBlocking(userId, aiReq);
            if (result == null) {
                return null;
            }
            String content = result.getContent();
            if (content == null || content.isBlank()) {
                return null;
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

            CharacterDiary diary = new CharacterDiary();
            diary.setCharacterId(characterId);
            diary.setUserId(userId);
            diary.setTitle(title.length() > 100 ? title.substring(0, 100) : title);
            diary.setContent(diaryContent);
            diary.setMood(null);
            diary.setConversationId(conversation.getId());
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
        } catch (Exception e) {
            log.debug("Diary generation failed for charId={}, reason={}", characterId, e.getMessage());
            return null;
        }
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

    private boolean shouldAttemptDiary() {
        double p = diaryTriggerProbability;
        if (p <= 0) {
            return false;
        }
        if (p >= 1) {
            return true;
        }
        return ThreadLocalRandom.current().nextDouble() < p;
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
