package com.lianyu.service.memory;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 长期记忆读路径缓存：优先 Redis，未命中再走 MySQL / Milvus。
 * 写入或删除记忆后必须调用 {@link #invalidate(Long, Long)}。
 */
@Slf4j
@Service
public class MemoryCacheService {

    private static final String PROFILE_PREFIX = "memory:profile:";
    private static final String RECENT_PREFIX = "memory:recent:";
    private static final String SEMANTIC_PREFIX = "memory:semantic:";

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};
    private static final TypeReference<List<CachedMemoryRow>> ROW_LIST = new TypeReference<>() {};

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @Value("${lianyu.memory.cache.enabled:true}")
    private boolean enabled;

    @Value("${lianyu.memory.cache.profile-ttl-hours:24}")
    private long profileTtlHours;

    @Value("${lianyu.memory.cache.recent-ttl-minutes:60}")
    private long recentTtlMinutes;

    @Value("${lianyu.memory.cache.semantic-ttl-minutes:15}")
    private long semanticTtlMinutes;

    public MemoryCacheService(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    public List<String> getProfileFacts(Long userId, Long characterId) {
        return getList(profileKey(userId, characterId));
    }

    public void putProfileFacts(Long userId, Long characterId, List<String> facts) {
        putList(profileKey(userId, characterId), facts, Duration.ofHours(Math.max(1, profileTtlHours)));
    }

    public List<CachedMemoryRow> getRecentRows(Long userId, Long characterId) {
        if (!enabled) {
            return null;
        }
        try {
            String json = redisTemplate.opsForValue().get(recentKey(userId, characterId));
            if (StrUtil.isBlank(json)) {
                return null;
            }
            return objectMapper.readValue(json, ROW_LIST);
        } catch (Exception e) {
            log.debug("memory recent cache read failed: {}", e.getMessage());
            return null;
        }
    }

    public void putRecentRows(Long userId, Long characterId, List<CachedMemoryRow> rows) {
        if (!enabled || rows == null) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(
                    recentKey(userId, characterId),
                    objectMapper.writeValueAsString(rows),
                    Duration.ofMinutes(Math.max(5, recentTtlMinutes)));
        } catch (Exception e) {
            log.debug("memory recent cache write failed: {}", e.getMessage());
        }
    }

    public List<String> getSemanticResults(Long userId, Long characterId, String query) {
        if (StrUtil.isBlank(query)) {
            return null;
        }
        String hash = hashQuery(query);
        String key = semanticKey(userId, characterId);
        try {
            Object raw = redisTemplate.opsForHash().get(key, hash);
            if (raw == null) {
                return null;
            }
            return objectMapper.readValue(raw.toString(), STRING_LIST);
        } catch (Exception e) {
            log.debug("memory semantic cache read failed: {}", e.getMessage());
            return null;
        }
    }

    public void putSemanticResults(Long userId, Long characterId, String query, List<String> results) {
        if (StrUtil.isBlank(query) || results == null) {
            return;
        }
        String hash = hashQuery(query);
        String key = semanticKey(userId, characterId);
        try {
            String json = objectMapper.writeValueAsString(results);
            redisTemplate.opsForHash().put(key, hash, json);
            redisTemplate.expire(key, Duration.ofMinutes(Math.max(1, semanticTtlMinutes)));
        } catch (Exception e) {
            log.debug("memory semantic cache write failed: {}", e.getMessage());
        }
    }

    public void invalidate(Long userId, Long characterId) {
        if (userId == null || characterId == null) {
            return;
        }
        try {
            redisTemplate.delete(List.of(
                    profileKey(userId, characterId),
                    recentKey(userId, characterId),
                    semanticKey(userId, characterId)
            ));
        } catch (Exception e) {
            log.warn("memory cache invalidate failed userId={} characterId={}: {}",
                    userId, characterId, e.getMessage());
        }
    }

    private List<String> getList(String key) {
        if (!enabled) {
            return null;
        }
        try {
            String json = redisTemplate.opsForValue().get(key);
            if (StrUtil.isBlank(json)) {
                return null;
            }
            return objectMapper.readValue(json, STRING_LIST);
        } catch (Exception e) {
            log.debug("memory cache read failed key={}: {}", key, e.getMessage());
            return null;
        }
    }

    private void putList(String key, List<String> values, Duration ttl) {
        if (!enabled || values == null) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(values), ttl);
        } catch (Exception e) {
            log.debug("memory cache write failed key={}: {}", key, e.getMessage());
        }
    }

    private static String profileKey(Long userId, Long characterId) {
        return PROFILE_PREFIX + userId + ":" + characterId;
    }

    private static String recentKey(Long userId, Long characterId) {
        return RECENT_PREFIX + userId + ":" + characterId;
    }

    private static String semanticKey(Long userId, Long characterId) {
        return SEMANTIC_PREFIX + userId + ":" + characterId;
    }

    static String hashQuery(String query) {
        String normalized = query.trim().replaceAll("\\s+", " ").toLowerCase();
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(normalized.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }
}
