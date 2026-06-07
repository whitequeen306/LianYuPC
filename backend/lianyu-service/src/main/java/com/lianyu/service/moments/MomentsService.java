package com.lianyu.service.moments;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lianyu.common.base.ErrorCode;
import com.lianyu.common.constant.AiConstants;
import com.lianyu.common.exception.BusinessException;
import com.lianyu.dao.entity.Character;
import com.lianyu.dao.entity.Conversation;
import com.lianyu.dao.entity.Message;
import com.lianyu.dao.entity.MomentsPost;
import com.lianyu.dao.mapper.CharacterMapper;
import com.lianyu.dao.mapper.ConversationMapper;
import com.lianyu.dao.mapper.MessageMapper;
import com.lianyu.dao.mapper.MomentsPostMapper;
import com.lianyu.service.ai.AiChatService;
import com.lianyu.service.ai.CharacterPromptBuilder;
import com.lianyu.service.dto.*;
import com.lianyu.service.memory.MemoryRetriever;
import com.lianyu.service.notification.NotificationService;
import com.lianyu.service.storage.FileStorageService;
import com.lianyu.service.support.OutputLanguageService;
import com.lianyu.service.tools.ChatToolContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class MomentsService {

    public static final String TYPE_MOOD = "MOOD";
    public static final String TYPE_REFLECTION = "REFLECTION";
    public static final String TYPE_SYSTEM = "SYSTEM";
    public static final String TYPE_USER = "USER";
    public static final String AUTHOR_CHARACTER = "CHARACTER";
    public static final String AUTHOR_USER = "USER";

    private static final String SEEN_KEY_PREFIX = "moments:feed-seen:";
    private static final String COOLDOWN_KEY_PREFIX = "moments:cooldown:";

    private final MomentsPostMapper momentsPostMapper;
    private final ConversationMapper conversationMapper;
    private final MessageMapper messageMapper;
    private final CharacterMapper characterMapper;
    private final AiChatService aiChatService;
    private final CharacterPromptBuilder promptBuilder;
    private final MemoryRetriever memoryRetriever;
    private final NotificationService notificationService;
    private final OutputLanguageService outputLanguageService;
    private final StringRedisTemplate redisTemplate;
    private final FileStorageService fileStorageService;
    private final MomentsCommentOrchestrator momentsCommentOrchestrator;

    @Value("${lianyu.moments.content-max-chars:180}")
    private int contentMaxChars;

    @Value("${lianyu.moments.min-interval-hours:4}")
    private int minIntervalHours;

    @Value("${lianyu.moments.max-posts-per-character-per-day:2}")
    private int maxPostsPerCharacterPerDay;

    @Value("${lianyu.ai.context-window:20}")
    private int contextWindow;

    public MomentFeedResponse listFeed(Long userId, Long characterId, Long cursor, int limit) {
        int realLimit = Math.min(Math.max(1, limit), 50);
        LambdaQueryWrapper<MomentsPost> qw = new LambdaQueryWrapper<MomentsPost>()
                .eq(MomentsPost::getUserId, userId)
                .orderByDesc(MomentsPost::getId);
        if (characterId != null) {
            qw.eq(MomentsPost::getCharacterId, characterId);
        }
        if (cursor != null && cursor > 0) {
            qw.lt(MomentsPost::getId, cursor);
        }
        qw.last("LIMIT " + (realLimit + 1));

        List<MomentsPost> rows = momentsPostMapper.selectList(qw);
        boolean hasMore = rows.size() > realLimit;
        if (hasMore) {
            rows = new ArrayList<>(rows.subList(0, realLimit));
        }

        Set<Long> characterIds = rows.stream()
                .map(MomentsPost::getCharacterId)
                .filter(id -> id != null && id > 0)
                .collect(Collectors.toSet());
        Map<Long, Character> characterMap = new HashMap<>();
        if (!characterIds.isEmpty()) {
            characterMapper.selectBatchIds(characterIds).forEach(c -> characterMap.put(c.getId(), c));
        }

        List<MomentPostResponse> items = rows.stream()
                .filter(row -> isUserAuthored(row) || characterMap.containsKey(row.getCharacterId()))
                .map(row -> toResponse(row, characterMap.get(row.getCharacterId())))
                .toList();

        Long nextCursor = hasMore && !rows.isEmpty() ? rows.get(rows.size() - 1).getId() : null;
        return MomentFeedResponse.builder()
                .items(items)
                .nextCursor(nextCursor)
                .hasMore(hasMore)
                .build();
    }

    public MomentUnreadCountResponse getUnreadCount(Long userId) {
        LocalDateTime seenAt = getFeedSeenAt(userId);
        LambdaQueryWrapper<MomentsPost> qw = new LambdaQueryWrapper<MomentsPost>()
                .eq(MomentsPost::getUserId, userId);
        if (seenAt != null) {
            qw.gt(MomentsPost::getCreatedAt, seenAt);
        }
        Long count = momentsPostMapper.selectCount(qw);
        return new MomentUnreadCountResponse(count == null ? 0L : count);
    }

    public void markFeedSeen(Long userId) {
        redisTemplate.opsForValue().set(
                SEEN_KEY_PREFIX + userId,
                LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                Duration.ofDays(90)
        );
    }

    @Transactional
    public MomentPostResponse createUserPost(Long userId, CreateMomentPostRequest request) {
        String content = sanitizeMomentText(request.getContent());
        if (content.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "动态内容不能为空");
        }

        String hash = sha256(userId + "|USER|" + System.nanoTime() + "|" + normalizeForHash(content));
        MomentsPost post = new MomentsPost();
        post.setUserId(userId);
        post.setAuthorType(AUTHOR_USER);
        post.setCharacterId(null);
        post.setConversationId(null);
        post.setContent(content);
        post.setPostType(TYPE_USER);
        post.setVisibility("PRIVATE");
        post.setMetaJson(Map.of("userAuthored", true));
        post.setSourceHash(hash);
        momentsPostMapper.insert(post);

        momentsCommentOrchestrator.afterPostCreated(post.getId());
        log.info("Moments user post created: userId={}, id={}", userId, post.getId());
        return toResponse(post, null);
    }

    /**
     * 定时任务入口：尝试为一条单聊会话生成朋友圈动态。
     *
     * @return 是否成功写入
     */
    @Transactional
    public boolean tryGenerateForConversation(Conversation conversation, Character character) {
        return tryGenerateForConversation(conversation, character, null, null);
    }

    @Transactional
    public boolean tryGenerateForConversation(Conversation conversation,
                                              Character character,
                                              Message latestUserMessageHint,
                                              Long todayPostsHint) {
        if (conversation == null || character == null) {
            return false;
        }
        if (!"SINGLE".equalsIgnoreCase(conversation.getMode())) {
            return false;
        }
        Long userId = conversation.getUserId();
        Long characterId = character.getId();
        if (isBlocked(character) || isDoNotDisturbActive(character)) {
            return false;
        }
        if (Boolean.TRUE.equals(redisTemplate.hasKey(cooldownKey(userId, characterId)))) {
            return false;
        }
        long postsToday = todayPostsHint != null ? Math.max(0L, todayPostsHint) : countPostsToday(userId, characterId);
        if (postsToday >= Math.max(1, maxPostsPerCharacterPerDay)) {
            return false;
        }

        String postType = pickPostType(conversation.getId(), latestUserMessageHint);
        GeneratedMoment generated = generateContent(userId, conversation, character, postType, latestUserMessageHint);
        if (generated == null || generated.content() == null || generated.content().isBlank()) {
            return false;
        }

        String hash = sha256(userId + "|" + characterId + "|" + generated.postType() + "|"
                + LocalDate.now() + "|" + normalizeForHash(generated.content()));
        if (momentsPostMapper.selectCount(new LambdaQueryWrapper<MomentsPost>()
                .eq(MomentsPost::getSourceHash, hash)) > 0) {
            return false;
        }

        MomentsPost post = new MomentsPost();
        post.setUserId(userId);
        post.setAuthorType(AUTHOR_CHARACTER);
        post.setCharacterId(characterId);
        post.setConversationId(conversation.getId());
        post.setContent(generated.content());
        post.setPostType(generated.postType());
        post.setVisibility("PRIVATE");
        post.setMetaJson(generated.meta());
        post.setSourceHash(hash);
        try {
            momentsPostMapper.insert(post);
        } catch (Exception e) {
            log.debug("Moments insert skipped (duplicate?): convId={}, reason={}", conversation.getId(), e.getMessage());
            return false;
        }

        setCooldown(userId, characterId);
        notificationService.notifyMomentPost(
                userId,
                conversation.getId(),
                characterId,
                character.getName(),
                post.getContent()
        );
        momentsCommentOrchestrator.afterPostCreated(post.getId());
        log.info("Moments post created: userId={}, character={}, type={}, id={}",
                userId, character.getName(), post.getPostType(), post.getId());
        return true;
    }

    private String pickPostType(Long conversationId, Message latestUserMessageHint) {
        Message lastUser = latestUserMessageHint != null ? latestUserMessageHint : findLastUserMessage(conversationId);
        double roll = ThreadLocalRandom.current().nextDouble();
        if (lastUser != null && lastUser.getCreatedAt() != null
                && lastUser.getCreatedAt().isAfter(LocalDateTime.now().minusHours(48))) {
            if (roll < 0.35) {
                return TYPE_REFLECTION;
            }
            if (roll < 0.55) {
                return TYPE_SYSTEM;
            }
            return TYPE_MOOD;
        }
        if (roll < 0.7) {
            return TYPE_MOOD;
        }
        return TYPE_REFLECTION;
    }

    private GeneratedMoment generateContent(Long userId,
                                            Conversation conversation,
                                            Character character,
                                            String postType,
                                            Message latestUserMessageHint) {
        return switch (postType) {
            case TYPE_SYSTEM -> generateSystemMoment(userId, conversation, character, latestUserMessageHint);
            case TYPE_REFLECTION -> generateReflectionMoment(userId, conversation, character);
            default -> generateMoodMoment(userId, character);
        };
    }

    private GeneratedMoment generateMoodMoment(Long userId, Character character) {
        String memoryContext = memoryRetriever.retrieveProfileContext(character.getId(), userId);
        String systemPrompt = buildSystemPrompt(userId, character, memoryContext, null);
        String content = callMomentModel(userId, character, systemPrompt, List.of(), """
                请以该角色身份发一条「朋友圈」式短动态（第一人称）。
                主题：今日心情、生活碎片或随口一想，与「用户」无直接对话也行。
                要求：20~120字；不要话题标签；不要解释自己是AI；不要写「朋友圈」三字；一条即可，不要换行分段。
                """);
        if (content == null) {
            return null;
        }
        return new GeneratedMoment(TYPE_MOOD, content, Map.of());
    }

    private GeneratedMoment generateReflectionMoment(Long userId, Conversation conversation, Character character) {
        List<Message> history = getRecentMessages(conversation.getId(), Math.min(12, contextWindow));
        if (history.isEmpty()) {
            return generateMoodMoment(userId, character);
        }
        String memoryContext = memoryRetriever.retrieveProfileContext(character.getId(), userId);
        String systemPrompt = buildSystemPrompt(userId, character, memoryContext, null);
        List<MessageDto> histDtos = history.stream().map(this::toMessageDto).toList();
        String content = callMomentModel(userId, character, systemPrompt, histDtos, """
                请以该角色身份发一条「朋友圈」式短动态（第一人称）。
                主题：基于最近和用户的聊天，写一点感想或余韵；可自然引用对话里的关键词，但不要照搬原句。
                要求：20~120字；不要话题标签；不要解释自己是AI；一条即可。
                """);
        if (content == null) {
            return null;
        }
        Map<String, Object> meta = Map.of("basedOnChat", true);
        return new GeneratedMoment(TYPE_REFLECTION, content, meta);
    }

    private GeneratedMoment generateSystemMoment(Long userId,
                                                 Conversation conversation,
                                                 Character character,
                                                 Message latestUserMessageHint) {
        Message lastUser = latestUserMessageHint != null
                ? latestUserMessageHint
                : findLastUserMessage(conversation.getId());
        if (lastUser == null || lastUser.getCreatedAt() == null) {
            return null;
        }
        LocalDateTime at = lastUser.getCreatedAt();
        String template;
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("systemEvent", true);
        if (at.isAfter(LocalDateTime.now().minusHours(24))) {
            template = pickOne(
                    "今天收到你的消息了，我会好好记在心里的。",
                    "你今天来找我聊天了，不知道为什么，心情一下子变好了。",
                    "刚才的对话还在脑海里转，谢谢你愿意和我说话。"
            );
            meta.put("event", "USER_CHATTED_TODAY");
        } else if (at.isAfter(LocalDateTime.now().minusDays(7))) {
            template = pickOne(
                    "这几天忙里偷闲会想起你，希望你也过得顺利。",
                    "上次聊天之后偶尔会想起那几句，挺惦记的。",
                    "虽然没天天聊，但想到你还在，就觉得很安心。"
            );
            meta.put("event", "USER_CHATTED_RECENTLY");
        } else {
            return null;
        }
        String content = sanitizeMomentText(template);
        return new GeneratedMoment(TYPE_SYSTEM, content, meta);
    }

    private String callMomentModel(Long userId,
                                   Character character,
                                   String systemPrompt,
                                   List<MessageDto> history,
                                   String userInstruction) {
        AiChatRequest aiRequest = new AiChatRequest();
        aiRequest.setProvider(AiConstants.PLATFORM_PROVIDER);
        ChatToolContext.bindTo(aiRequest, character);
        List<MessageDto> messages = new ArrayList<>();
        messages.add(messageDto("system", systemPrompt));
        messages.addAll(history);
        messages.add(messageDto("user", userInstruction));
        aiRequest.setMessages(messages);
        try {
            ChatResult result = aiChatService.chatBlocking(userId, aiRequest);
            return sanitizeMomentText(result.getContent());
        } catch (Exception e) {
            log.debug("Moments AI generation failed: {}", e.getMessage());
            return null;
        }
    }

    private String buildSystemPrompt(Long userId, Character character, String memoryContext, String userInput) {
        String lang = outputLanguageService.resolveForRequest(userId, userInput);
        String base = promptBuilder.buildSystemPrompt(character, memoryContext, lang, true);
        return base + outputLanguageService.buildNaturalStyleBlock(lang);
    }

    private List<Message> getRecentMessages(Long conversationId, int limit) {
        List<Message> messages = messageMapper.selectList(new LambdaQueryWrapper<Message>()
                .eq(Message::getConversationId, conversationId)
                .orderByDesc(Message::getSeq)
                .last("LIMIT " + limit));
        Collections.reverse(messages);
        return messages;
    }

    private Message findLastUserMessage(Long conversationId) {
        return messageMapper.selectList(new LambdaQueryWrapper<Message>()
                        .eq(Message::getConversationId, conversationId)
                        .eq(Message::getRole, "USER")
                        .orderByDesc(Message::getSeq)
                        .last("LIMIT 1"))
                .stream()
                .findFirst()
                .orElse(null);
    }

    private long countPostsToday(Long userId, Long characterId) {
        LocalDateTime start = LocalDate.now().atStartOfDay();
        Long count = momentsPostMapper.selectCount(new LambdaQueryWrapper<MomentsPost>()
                .eq(MomentsPost::getUserId, userId)
                .eq(MomentsPost::getCharacterId, characterId)
                .ge(MomentsPost::getCreatedAt, start));
        return count == null ? 0L : count;
    }

    private void setCooldown(Long userId, Long characterId) {
        int hours = Math.max(1, minIntervalHours);
        redisTemplate.opsForValue().set(cooldownKey(userId, characterId), "1", Duration.ofHours(hours));
    }

    private String cooldownKey(Long userId, Long characterId) {
        return COOLDOWN_KEY_PREFIX + userId + ":" + characterId;
    }

    private LocalDateTime getFeedSeenAt(Long userId) {
        String raw = redisTemplate.opsForValue().get(SEEN_KEY_PREFIX + userId);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(raw, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        } catch (Exception e) {
            return null;
        }
    }

    private String sanitizeMomentText(String raw) {
        return MomentsTextSanitizer.sanitize(raw, contentMaxChars, 8);
    }

    private MessageDto toMessageDto(Message msg) {
        MessageDto dto = new MessageDto();
        dto.setRole(msg.getRole().toLowerCase());
        dto.setContent(msg.getContent());
        return dto;
    }

    private MessageDto messageDto(String role, String content) {
        MessageDto dto = new MessageDto();
        dto.setRole(role);
        dto.setContent(content);
        return dto;
    }

    private MomentPostResponse toResponse(MomentsPost row, Character character) {
        boolean userAuthored = isUserAuthored(row);
        return MomentPostResponse.builder()
                .id(row.getId())
                .authorType(userAuthored ? AUTHOR_USER : AUTHOR_CHARACTER)
                .characterId(row.getCharacterId())
                .characterName(userAuthored ? null : (character != null ? character.getName() : "角色"))
                .characterAvatarUrl(userAuthored ? null : (character != null
                        ? fileStorageService.resolvePublicUrl(character.getAvatarUrl())
                        : null))
                .userDisplayName(userAuthored ? "你" : null)
                .imageUrl(row.getImageUrl() != null
                        ? fileStorageService.resolvePublicUrl(row.getImageUrl())
                        : null)
                .conversationId(row.getConversationId())
                .content(row.getContent())
                .postType(row.getPostType())
                .metaJson(row.getMetaJson())
                .createdAt(row.getCreatedAt())
                .build();
    }

    private static boolean isUserAuthored(MomentsPost row) {
        return row != null && AUTHOR_USER.equalsIgnoreCase(row.getAuthorType());
    }

    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "hash failed");
        }
    }

    private String normalizeForHash(String content) {
        return content == null ? "" : content.trim().toLowerCase(Locale.ROOT);
    }

    private String pickOne(String... options) {
        return options[ThreadLocalRandom.current().nextInt(options.length)];
    }

    private boolean isBlocked(Character character) {
        if (character == null || character.getSettings() == null) {
            return false;
        }
        Object raw = character.getSettings().get("blocked");
        if (raw instanceof Boolean b) {
            return b;
        }
        if (raw instanceof String s) {
            return Boolean.parseBoolean(s);
        }
        return false;
    }

    private boolean isDoNotDisturbActive(Character character) {
        if (character == null || character.getSettings() == null) {
            return false;
        }
        Map<String, Object> settings = character.getSettings();
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

    private record GeneratedMoment(String postType, String content, Map<String, Object> meta) {
    }
}
