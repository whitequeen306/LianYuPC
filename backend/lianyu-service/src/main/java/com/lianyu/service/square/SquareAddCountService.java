package com.lianyu.service.square;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lianyu.dao.entity.CharacterSquareTemplate;
import com.lianyu.dao.mapper.CharacterSquareTemplateMapper;
import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 广场模板累计添加次数：MySQL 为权威源；Redis Hash 缓存（field=slug，value=count）。
 * 写入路径：先更新 DB，再删除整个 hash，读时 miss 再回填（避免双写不一致）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SquareAddCountService {

    static final String REDIS_HASH_KEY = "square:template_add_counts";
    private static final Duration CACHE_TTL = Duration.ofHours(6);

    private final CharacterSquareTemplateMapper templateMapper;
    private final StringRedisTemplate stringRedisTemplate;

    /** Flyway 合并/回填后避免读到旧 hash；启动时清一次再按需回填。 */
    @PostConstruct
    void clearStaleCacheOnBoot() {
        invalidateCache();
    }

    public void incrementAndInvalidate(Long templateId) {
        if (templateId == null) {
            return;
        }
        templateMapper.incrementAddCount(templateId);
        invalidateCache();
    }

    public void invalidateCache() {
        try {
            Boolean deleted = stringRedisTemplate.delete(REDIS_HASH_KEY);
            log.debug("Square add-count cache invalidated: deleted={}", deleted);
        } catch (Exception e) {
            log.warn("Square add-count cache invalidate failed: {}", e.getMessage());
        }
    }

    /** slug -> count；优先 Redis Hash，miss 则从 DB 全量回填。 */
    public Map<String, Long> getCountsBySlug() {
        try {
            Map<Object, Object> cached = stringRedisTemplate.opsForHash().entries(REDIS_HASH_KEY);
            if (cached != null && !cached.isEmpty()) {
                Map<String, Long> result = new HashMap<>();
                cached.forEach((k, v) -> {
                    if (k == null || v == null) {
                        return;
                    }
                    try {
                        result.put(String.valueOf(k), Long.parseLong(String.valueOf(v)));
                    } catch (NumberFormatException ignored) {
                        // skip bad field
                    }
                });
                if (!result.isEmpty()) {
                    return result;
                }
            }
        } catch (Exception e) {
            log.warn("Square add-count cache read failed, falling back to DB: {}", e.getMessage());
        }
        return loadFromDbAndCache();
    }

    public long getCount(String slug) {
        if (slug == null || slug.isBlank()) {
            return 0L;
        }
        return getCountsBySlug().getOrDefault(slug.trim().toLowerCase(), 0L);
    }

    private Map<String, Long> loadFromDbAndCache() {
        List<CharacterSquareTemplate> rows = templateMapper.selectList(
                new LambdaQueryWrapper<CharacterSquareTemplate>()
                        .isNotNull(CharacterSquareTemplate::getSlug));
        Map<String, Long> result = new HashMap<>();
        Map<String, String> hash = new HashMap<>();
        for (CharacterSquareTemplate row : rows) {
            if (row.getSlug() == null || row.getSlug().isBlank()) {
                continue;
            }
            String slug = row.getSlug().trim().toLowerCase();
            long count = row.getAddCount() == null ? 0L : Math.max(0L, row.getAddCount());
            result.put(slug, count);
            hash.put(slug, Long.toString(count));
        }
        try {
            if (!hash.isEmpty()) {
                stringRedisTemplate.opsForHash().putAll(REDIS_HASH_KEY, hash);
                stringRedisTemplate.expire(REDIS_HASH_KEY, CACHE_TTL);
            }
        } catch (Exception e) {
            log.warn("Square add-count cache warm failed: {}", e.getMessage());
        }
        return result;
    }
}
