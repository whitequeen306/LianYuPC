package com.lianyu.service.character;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lianyu.common.i18n.OutputLanguage;
import com.lianyu.dao.entity.Character;
import com.lianyu.dao.entity.CharacterDiary;
import com.lianyu.dao.entity.MomentsComment;
import com.lianyu.dao.entity.MomentsPost;
import com.lianyu.dao.mapper.CharacterDiaryMapper;
import com.lianyu.dao.mapper.CharacterMapper;
import com.lianyu.dao.mapper.MomentsCommentMapper;
import com.lianyu.dao.mapper.MomentsPostMapper;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CharacterRecentActivityService {

    private static final String CACHE_PREFIX = "activity:prompt:";
    private static final Duration CACHE_TTL = Duration.ofHours(1);
    private static final int MAX_LINES = 5;
    private static final int SNIPPET_CHARS = 40;

    private final CharacterDiaryMapper characterDiaryMapper;
    private final MomentsPostMapper momentsPostMapper;
    private final MomentsCommentMapper momentsCommentMapper;
    private final CharacterMapper characterMapper;
    private final StringRedisTemplate redisTemplate;

    /** 文案归 Python（app/prompts/recent_life_*）；取不到就不注入本块，也不缓存失败结果。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.lianyu.service.agent.SceneInstructionPort promptPort;

    public String formatForPrompt(Long userId, Long characterId, String lang) {
        if (userId == null || characterId == null) {
            return "";
        }
        String cacheKey = cacheKey(userId, characterId);
        String cached = redisTemplate.opsForValue().get(cacheKey);
        if (cached != null) {
            return cached;
        }
        String formatted = buildFormatted(userId, characterId, lang);
        if (formatted == null) {
            // 端口不可用/模板缺失：不缓存，等下一轮再试，避免把空块钉在 Redis 里一小时。
            return "";
        }
        redisTemplate.opsForValue().set(cacheKey, formatted, CACHE_TTL);
        return formatted;
    }

    public void evictCache(Long userId, Long characterId) {
        if (userId == null || characterId == null) {
            return;
        }
        redisTemplate.delete(cacheKey(userId, characterId));
    }

    private String buildFormatted(Long userId, Long characterId, String lang) {
        String sectionTitle = renderTitle(lang);
        String closing = promptPort == null
                ? null
                : promptPort.render("recent_life_closing", Map.of(), 0);
        if (sectionTitle == null || closing == null) {
            return null;
        }
        LocalDateTime since = LocalDateTime.now().minusDays(7);
        List<ActivityLine> lines = new ArrayList<>();

        List<CharacterDiary> diaries = characterDiaryMapper.selectList(new LambdaQueryWrapper<CharacterDiary>()
                .eq(CharacterDiary::getUserId, userId)
                .eq(CharacterDiary::getCharacterId, characterId)
                .ge(CharacterDiary::getCreatedAt, since)
                .orderByDesc(CharacterDiary::getCreatedAt)
                .last("LIMIT 2"));
        for (CharacterDiary diary : diaries) {
            String title = diary.getTitle() != null ? diary.getTitle().trim() : "";
            String body = diary.getContent() != null ? clip(diary.getContent()) : "";
            String detail = title.isBlank() ? body : "《" + clip(title) + "》——" + body;
            lines.add(new ActivityLine(diary.getCreatedAt(), formatDiaryLine(lang, detail)));
        }

        List<MomentsPost> posts = momentsPostMapper.selectList(new LambdaQueryWrapper<MomentsPost>()
                .eq(MomentsPost::getAuthorType, "CHARACTER")
                .eq(MomentsPost::getCharacterId, characterId)
                .eq(MomentsPost::getUserId, userId)
                .ge(MomentsPost::getCreatedAt, since)
                .orderByDesc(MomentsPost::getCreatedAt)
                .last("LIMIT 2"));
        for (MomentsPost post : posts) {
            String content = post.getContent() != null ? clip(post.getContent()) : "";
            lines.add(new ActivityLine(post.getCreatedAt(), formatPostLine(lang, content)));
        }

        List<MomentsComment> comments = momentsCommentMapper.selectList(new LambdaQueryWrapper<MomentsComment>()
                .eq(MomentsComment::getCharacterId, characterId)
                .eq(MomentsComment::getAuthorType, "CHARACTER")
                .ge(MomentsComment::getCreatedAt, since)
                .orderByDesc(MomentsComment::getCreatedAt)
                .last("LIMIT 2"));
        Map<Long, MomentsPost> postsById = loadPosts(comments);
        Map<Long, Character> charactersById = loadCharacters(postsById);
        for (MomentsComment comment : comments) {
            MomentsPost post = postsById.get(comment.getPostId());
            String peerName = resolvePeerName(post, charactersById);
            String content = comment.getContent() != null ? clip(comment.getContent()) : "";
            lines.add(new ActivityLine(comment.getCreatedAt(), formatCommentLine(lang, peerName, content)));
        }

        if (lines.isEmpty()) {
            return "";
        }

        lines.sort(Comparator.comparing(ActivityLine::at).reversed());
        if (lines.size() > MAX_LINES) {
            lines = lines.subList(0, MAX_LINES);
        }

        StringBuilder sb = new StringBuilder(sectionTitle);
        for (ActivityLine line : lines) {
            sb.append("\n- ").append(formatDate(lang, line.at())).append("：").append(line.text());
        }
        sb.append("\n\n").append(closing);
        return sb.toString();
    }

    private String renderTitle(String lang) {
        if (promptPort == null) {
            return null;
        }
        String suffix = switch (OutputLanguage.fromCode(lang)) {
            case EN -> "en";
            case JA -> "ja";
            case ZH_TW -> "zh_tw";
            default -> "zh";
        };
        return promptPort.render("recent_life_title_" + suffix, Map.of(), 0);
    }

    private Map<Long, MomentsPost> loadPosts(List<MomentsComment> comments) {
        List<Long> postIds = comments.stream()
                .map(MomentsComment::getPostId)
                .filter(id -> id != null && id > 0)
                .distinct()
                .toList();
        if (postIds.isEmpty()) {
            return Map.of();
        }
        return momentsPostMapper.selectBatchIds(postIds).stream()
                .collect(Collectors.toMap(MomentsPost::getId, p -> p, (a, b) -> a));
    }

    private Map<Long, Character> loadCharacters(Map<Long, MomentsPost> postsById) {
        List<Long> characterIds = postsById.values().stream()
                .filter(p -> "CHARACTER".equalsIgnoreCase(p.getAuthorType()) && p.getCharacterId() != null)
                .map(MomentsPost::getCharacterId)
                .distinct()
                .toList();
        if (characterIds.isEmpty()) {
            return Map.of();
        }
        return characterMapper.selectBatchIds(characterIds).stream()
                .collect(Collectors.toMap(Character::getId, c -> c, (a, b) -> a));
    }

    private String resolvePeerName(MomentsPost post, Map<Long, Character> charactersById) {
        if (post == null) {
            return "某人";
        }
        if ("CHARACTER".equalsIgnoreCase(post.getAuthorType()) && post.getCharacterId() != null) {
            Character author = charactersById.get(post.getCharacterId());
            if (author != null && author.getName() != null && !author.getName().isBlank()) {
                return author.getName().trim();
            }
        }
        return "用户";
    }

    private static String formatDiaryLine(String lang, String detail) {
        return switch (OutputLanguage.fromCode(lang)) {
            case EN -> "Wrote diary " + detail;
            case JA -> "日記を書いた " + detail;
            default -> "写了日记" + detail;
        };
    }

    private static String formatPostLine(String lang, String content) {
        return switch (OutputLanguage.fromCode(lang)) {
            case EN -> "Posted on moments \"" + content + "\"";
            case JA -> "モーメンツに投稿「" + content + "」";
            default -> "发了朋友圈「" + content + "」";
        };
    }

    private static String formatCommentLine(String lang, String peerName, String content) {
        return switch (OutputLanguage.fromCode(lang)) {
            case EN -> "Commented on " + peerName + "'s post: " + content;
            case JA -> peerName + " の投稿にコメント：" + content;
            default -> "在 " + peerName + " 的动态下评论了「" + content + "」";
        };
    }

    private static String formatDate(String lang, LocalDateTime at) {
        if (at == null) {
            return "";
        }
        return switch (OutputLanguage.fromCode(lang)) {
            case EN -> at.format(DateTimeFormatter.ofPattern("MMM dd", Locale.ENGLISH));
            default -> at.getMonthValue() + "月" + at.getDayOfMonth() + "日";
        };
    }

    private static String clip(String text) {
        String trimmed = text.trim().replaceAll("\\s+", " ");
        if (trimmed.length() <= SNIPPET_CHARS) {
            return trimmed;
        }
        return trimmed.substring(0, SNIPPET_CHARS) + "…";
    }

    private static String cacheKey(Long userId, Long characterId) {
        return CACHE_PREFIX + userId + ":" + characterId;
    }

    private record ActivityLine(LocalDateTime at, String text) {}
}
