package com.lianyu.service.memory;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lianyu.dao.entity.MemoryMeta;
import com.lianyu.dao.mapper.MemoryMetaMapper;
import java.time.Duration;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryMilvusSyncService {

    private static final String RESYNC_DONE_KEY = "memory:milvus:resync:v2:done";

    private final MemoryMetaMapper memoryMetaMapper;
    private final MemoryVectorStore memoryVectorStore;
    private final StringRedisTemplate redisTemplate;

    @Value("${lianyu.memory.milvus.resync-on-startup:true}")
    private boolean resyncOnStartup;

    @Order(100)
    @EventListener(ApplicationReadyEvent.class)
    public void resyncOnStartupIfNeeded() {
        if (!resyncOnStartup) {
            return;
        }
        if (Boolean.TRUE.equals(redisTemplate.hasKey(RESYNC_DONE_KEY))) {
            log.info("Milvus v2 resync already completed, skipping");
            return;
        }
        int synced = resyncAll();
        if (synced >= 0) {
            redisTemplate.opsForValue().set(RESYNC_DONE_KEY, "1", Duration.ofDays(3650));
            log.info("Milvus v2 resync completed: {} memories synced", synced);
        }
    }

    public int resyncAll() {
        List<MemoryMeta> all = memoryMetaMapper.selectList(
                new LambdaQueryWrapper<MemoryMeta>()
                        .isNotNull(MemoryMeta::getSummary)
                        .ne(MemoryMeta::getSummary, ""));
        int synced = 0;
        for (MemoryMeta meta : all) {
            if (repairOne(meta.getId())) {
                synced++;
            }
        }
        return synced;
    }

    public boolean repairOne(Long memoryId) {
        if (memoryId == null) {
            return false;
        }
        MemoryMeta meta = memoryMetaMapper.selectById(memoryId);
        if (meta == null || meta.getSummary() == null || meta.getSummary().isBlank()) {
            return false;
        }
        String oldVecId = meta.getMilvusVecId();
        if (oldVecId != null && !oldVecId.isBlank()) {
            memoryVectorStore.delete(List.of(oldVecId));
        }
        String vecId = memoryVectorStore.insert(
                meta.getCharacterId(),
                meta.getUserId(),
                meta.getId(),
                meta.getSummary(),
                meta.getMemoryType());
        if (vecId == null) {
            log.warn("Milvus repair failed for memoryId={}", memoryId);
            return false;
        }
        meta.setMilvusVecId(vecId);
        memoryMetaMapper.updateById(meta);
        return true;
    }
}
