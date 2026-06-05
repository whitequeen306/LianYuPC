package com.lianyu.service.square;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.lianyu.common.base.ErrorCode;
import com.lianyu.common.exception.BusinessException;
import com.lianyu.dao.entity.CharacterSquareTemplate;
import com.lianyu.dao.entity.SquareLike;
import com.lianyu.dao.mapper.CharacterSquareTemplateMapper;
import com.lianyu.dao.mapper.SquareLikeMapper;
import com.lianyu.service.dto.SquareLikeToggleResponse;
import jakarta.annotation.PostConstruct;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class SquareLikeService {

    private static final String KEY_LIKES = "square:likes";
    private static final String KEY_USER_LIKES_PREFIX = "square:user:";

    private final SquareLikeMapper squareLikeMapper;
    private final CharacterSquareTemplateMapper templateMapper;
    private final StringRedisTemplate redisTemplate;

    @PostConstruct
    public void warmUpLikeCache() {
        Long size = redisTemplate.opsForZSet().size(KEY_LIKES);
        if (size != null && size > 0) {
            return;
        }
        syncLikeCountsFromDb();
    }

    public Map<Long, Long> getLikeCounts() {
        Set<ZSetOperations.TypedTuple<String>> tuples =
                redisTemplate.opsForZSet().rangeWithScores(KEY_LIKES, 0, -1);
        if (tuples == null || tuples.isEmpty()) {
            return Map.of();
        }
        Map<Long, Long> counts = new HashMap<>();
        for (ZSetOperations.TypedTuple<String> tuple : tuples) {
            if (tuple.getValue() == null || tuple.getScore() == null) {
                continue;
            }
            try {
                long templateId = Long.parseLong(tuple.getValue());
                long score = Math.max(0L, tuple.getScore().longValue());
                if (score > 0) {
                    counts.put(templateId, score);
                }
            } catch (NumberFormatException ignored) {
                // skip malformed member
            }
        }
        return counts;
    }

    public Set<Long> getUserLikes(Long userId) {
        if (userId == null) {
            return Set.of();
        }
        String key = userLikesKey(userId);
        Set<String> members = redisTemplate.opsForSet().members(key);
        if (members == null || members.isEmpty()) {
            Set<Long> fromDb = loadUserLikesFromDb(userId);
            if (!fromDb.isEmpty()) {
                String[] values = fromDb.stream().map(String::valueOf).toArray(String[]::new);
                redisTemplate.opsForSet().add(key, values);
            }
            return fromDb;
        }
        Set<Long> liked = new HashSet<>();
        for (String member : members) {
            try {
                liked.add(Long.parseLong(member));
            } catch (NumberFormatException ignored) {
                // skip malformed member
            }
        }
        return liked;
    }

    @Transactional
    public SquareLikeToggleResponse toggleLike(Long userId, Long templateId) {
        CharacterSquareTemplate template = templateMapper.selectById(templateId);
        if (template == null || template.getIsEnabled() == null || template.getIsEnabled() != 1) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "角色模板不存在或已下架");
        }

        String templateKey = String.valueOf(templateId);
        String userKey = userLikesKey(userId);
        boolean liked = Boolean.TRUE.equals(redisTemplate.opsForSet().isMember(userKey, templateKey));
        if (!liked) {
            Set<Long> dbLikes = loadUserLikesFromDb(userId);
            liked = dbLikes.contains(templateId);
        }

        if (liked) {
            redisTemplate.opsForSet().remove(userKey, templateKey);
            redisTemplate.opsForZSet().incrementScore(KEY_LIKES, templateKey, -1D);
            normalizeScore(templateId);
            markDeletedInDb(userId, templateId);
            long likeCount = getLikeCount(templateId);
            return SquareLikeToggleResponse.builder().liked(false).likeCount(likeCount).build();
        }

        redisTemplate.opsForSet().add(userKey, templateKey);
        redisTemplate.opsForZSet().incrementScore(KEY_LIKES, templateKey, 1D);
        upsertActiveLikeInDb(userId, templateId);
        long likeCount = getLikeCount(templateId);
        return SquareLikeToggleResponse.builder().liked(true).likeCount(likeCount).build();
    }

    public long getLikeCount(Long templateId) {
        Double score = redisTemplate.opsForZSet().score(KEY_LIKES, String.valueOf(templateId));
        if (score == null) {
            return countActiveLikesInDb(templateId);
        }
        return Math.max(0L, score.longValue());
    }

    private void syncLikeCountsFromDb() {
        List<SquareLike> activeLikes = squareLikeMapper.selectList(new LambdaQueryWrapper<SquareLike>()
                .eq(SquareLike::getIsDeleted, 0));
        if (activeLikes.isEmpty()) {
            return;
        }
        Map<Long, Long> grouped = activeLikes.stream()
                .collect(Collectors.groupingBy(SquareLike::getTemplateId, Collectors.counting()));
        for (Map.Entry<Long, Long> entry : grouped.entrySet()) {
            redisTemplate.opsForZSet().add(KEY_LIKES, String.valueOf(entry.getKey()), entry.getValue().doubleValue());
        }
        log.info("Square like cache warmed from DB: {} templates", grouped.size());
    }

    private Set<Long> loadUserLikesFromDb(Long userId) {
        List<SquareLike> rows = squareLikeMapper.selectList(new LambdaQueryWrapper<SquareLike>()
                .eq(SquareLike::getUserId, userId)
                .eq(SquareLike::getIsDeleted, 0));
        if (rows.isEmpty()) {
            return Collections.emptySet();
        }
        return rows.stream().map(SquareLike::getTemplateId).collect(Collectors.toSet());
    }

    private long countActiveLikesInDb(Long templateId) {
        Long count = squareLikeMapper.selectCount(new LambdaQueryWrapper<SquareLike>()
                .eq(SquareLike::getTemplateId, templateId)
                .eq(SquareLike::getIsDeleted, 0));
        return count != null ? count : 0L;
    }

    private void markDeletedInDb(Long userId, Long templateId) {
        squareLikeMapper.update(null, new LambdaUpdateWrapper<SquareLike>()
                .eq(SquareLike::getUserId, userId)
                .eq(SquareLike::getTemplateId, templateId)
                .set(SquareLike::getIsDeleted, 1));
    }

    private void upsertActiveLikeInDb(Long userId, Long templateId) {
        SquareLike existing = squareLikeMapper.selectOne(new LambdaQueryWrapper<SquareLike>()
                .eq(SquareLike::getUserId, userId)
                .eq(SquareLike::getTemplateId, templateId)
                .last("LIMIT 1"));
        if (existing != null) {
            if (existing.getIsDeleted() != null && existing.getIsDeleted() == 0) {
                return;
            }
            squareLikeMapper.update(null, new LambdaUpdateWrapper<SquareLike>()
                    .eq(SquareLike::getId, existing.getId())
                    .set(SquareLike::getIsDeleted, 0));
            return;
        }
        SquareLike row = new SquareLike();
        row.setUserId(userId);
        row.setTemplateId(templateId);
        row.setIsDeleted(0);
        try {
            squareLikeMapper.insert(row);
        } catch (DuplicateKeyException e) {
            squareLikeMapper.update(null, new LambdaUpdateWrapper<SquareLike>()
                    .eq(SquareLike::getUserId, userId)
                    .eq(SquareLike::getTemplateId, templateId)
                    .set(SquareLike::getIsDeleted, 0));
        }
    }

    private void normalizeScore(Long templateId) {
        String member = String.valueOf(templateId);
        Double score = redisTemplate.opsForZSet().score(KEY_LIKES, member);
        if (score != null && score <= 0D) {
            redisTemplate.opsForZSet().remove(KEY_LIKES, member);
        }
    }

    private static String userLikesKey(Long userId) {
        return KEY_USER_LIKES_PREFIX + userId + ":likes";
    }
}
