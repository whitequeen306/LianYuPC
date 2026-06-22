package com.lianyu.service.memory;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lianyu.dao.entity.MemoryMeta;
import com.lianyu.dao.entity.Message;
import com.lianyu.dao.enums.MemoryType;
import com.lianyu.dao.mapper.MemoryMetaMapper;
import com.lianyu.dao.mapper.MessageMapper;
import java.io.Serializable;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryWriter {

    private static final String EXCHANGE = "lianyu.exchange";
    private static final String ROUTING_KEY = "memory.summary";
    private static final String ENQUEUE_DEBOUNCE_PREFIX = "memory:summary:debounce:";

    private final MessageMapper messageMapper;
    private final MemoryMetaMapper memoryMetaMapper;
    private final RabbitTemplate rabbitTemplate;
    private final MemoryCacheService memoryCacheService;
    private final MemoryExtractionService memoryExtractionService;
    private final MemoryVectorStore memoryVectorStore;
    private final MemoryMilvusSyncService memoryMilvusSyncService;
    private final StringRedisTemplate redisTemplate;

    public void enqueueSummary(Long conversationId, Long characterId, Long userId) {
        String debounceKey = ENQUEUE_DEBOUNCE_PREFIX + conversationId + ":" + characterId;
        Boolean first = redisTemplate.opsForValue().setIfAbsent(debounceKey, "1", Duration.ofSeconds(30));
        if (Boolean.FALSE.equals(first)) {
            log.debug("Memory summary debounced: conversationId={}, characterId={}", conversationId, characterId);
            return;
        }
        MemorySummaryTask task = new MemorySummaryTask(conversationId, characterId, userId);
        rabbitTemplate.convertAndSend(EXCHANGE, ROUTING_KEY, task);
        log.info("Memory summary enqueued: conversationId={}, characterId={}", conversationId, characterId);
    }

    public void processSummary(MemorySummaryTask task) {
        try {
            List<Message> recentMsgs = messageMapper.selectList(
                    new LambdaQueryWrapper<Message>()
                            .eq(Message::getConversationId, task.conversationId())
                            .orderByDesc(Message::getSeq)
                            .last("LIMIT 30"));

            if (recentMsgs.isEmpty()) {
                return;
            }

            Collections.reverse(recentMsgs);
            List<ExtractedMemory> extracted = memoryExtractionService.extract(recentMsgs, task);
            if (extracted.isEmpty()) {
                log.debug("Skip memory write: no extracted memories, convId={}", task.conversationId());
                return;
            }

            int created = 0;
            int updated = 0;
            int skipped = 0;
            for (ExtractedMemory memory : extracted) {
                List<Long> sourceIds = memory.sourceMsgId() != null
                        ? List.of(memory.sourceMsgId())
                        : List.of();
                MemoryUpsertResult result = upsertTypedMemory(
                        task,
                        sourceIds,
                        memory.summary(),
                        memory.memoryType(),
                        memory.importance());
                switch (result) {
                    case CREATED -> created++;
                    case UPDATED -> updated++;
                    case SKIPPED -> skipped++;
                }
                String sourceHash = resolveSourceHash(task.userId(), task.characterId(), sourceIds, memory.summary());
                Long memoryId = findMemoryIdBySourceHash(sourceHash);
                if (memoryId != null) {
                    MemoryMeta saved = memoryMetaMapper.selectById(memoryId);
                    if (saved != null && (saved.getMilvusVecId() == null || saved.getMilvusVecId().isBlank())) {
                        memoryMilvusSyncService.repairOne(memoryId);
                    }
                }
            }

            log.info("Memory upsert done: convId={}, created={}, updated={}, skipped={}",
                    task.conversationId(), created, updated, skipped);
            if (created > 0 || updated > 0) {
                memoryCacheService.invalidate(task.userId(), task.characterId());
            }
        } catch (Exception e) {
            log.error("Memory processing failed for conversation {}", task.conversationId(), e);
            throw new RuntimeException("memory summary process failed, convId=" + task.conversationId(), e);
        }
    }

    public void deleteVectors(List<String> vectorIds) {
        memoryVectorStore.delete(vectorIds);
    }

    private MemoryUpsertResult upsertTypedMemory(MemorySummaryTask task,
                                                 List<Long> sourceIds,
                                                 String summary,
                                                 MemoryType memoryType,
                                                 double importance) {
        String sourceHash = resolveSourceHash(task.userId(), task.characterId(), sourceIds, summary);
        MemoryMeta existing = findExistingMemory(sourceHash, task.userId(), task.characterId(), summary);
        BigDecimal importanceValue = toImportance(importance);

        if (existing == null) {
            MemoryMeta meta = new MemoryMeta();
            meta.setCharacterId(task.characterId());
            meta.setUserId(task.userId());
            meta.setSummary(summary);
            meta.setMemoryType(memoryType);
            meta.setImportance(importanceValue);
            meta.setSourceMsgIds(sourceIds);
            meta.setSourceHash(sourceHash);
            try {
                memoryMetaMapper.insert(meta);
            } catch (DuplicateKeyException e) {
                MemoryMeta raced = findExistingMemory(sourceHash, task.userId(), task.characterId(), summary);
                if (raced != null) {
                    return MemoryUpsertResult.SKIPPED;
                }
                throw e;
            }

            String vecId = memoryVectorStore.insert(
                    task.characterId(), task.userId(), meta.getId(), summary, memoryType);
            if (vecId != null) {
                meta.setMilvusVecId(vecId);
                memoryMetaMapper.updateById(meta);
            } else {
                log.warn("Milvus insert returned null: memoryId={}, convCharacter={}/{}",
                        meta.getId(), task.characterId(), task.userId());
            }
            return MemoryUpsertResult.CREATED;
        }

        if (summary.equals(existing.getSummary())) {
            existing.setSourceMsgIds(mergeSourceIds(existing.getSourceMsgIds(), sourceIds));
            if (!sourceHash.equals(existing.getSourceHash())) {
                existing.setSourceHash(sourceHash);
            }
            if (importanceValue.compareTo(existing.getImportance()) > 0) {
                existing.setImportance(importanceValue);
            }
            memoryMetaMapper.updateById(existing);
            return MemoryUpsertResult.SKIPPED;
        }

        String oldVecId = existing.getMilvusVecId();
        existing.setSummary(summary);
        existing.setMemoryType(memoryType);
        existing.setImportance(importanceValue);
        existing.setSourceMsgIds(mergeSourceIds(existing.getSourceMsgIds(), sourceIds));
        existing.setSourceHash(sourceHash);
        memoryMetaMapper.updateById(existing);

        if (oldVecId != null && !oldVecId.isBlank()) {
            memoryVectorStore.delete(List.of(oldVecId));
        }
        String vecId = memoryVectorStore.insert(
                task.characterId(), task.userId(), existing.getId(), summary, memoryType);
        if (vecId != null) {
            existing.setMilvusVecId(vecId);
            memoryMetaMapper.updateById(existing);
        } else {
            log.warn("Milvus insert returned null on update: memoryId={}", existing.getId());
        }
        return MemoryUpsertResult.UPDATED;
    }

    private Long findMemoryIdBySourceHash(String sourceHash) {
        MemoryMeta meta = memoryMetaMapper.selectOne(
                new LambdaQueryWrapper<MemoryMeta>()
                        .eq(MemoryMeta::getSourceHash, sourceHash)
                        .last("LIMIT 1"));
        return meta != null ? meta.getId() : null;
    }

    private MemoryMeta findExistingMemory(String sourceHash, Long userId, Long characterId, String summary) {
        MemoryMeta byHash = memoryMetaMapper.selectOne(
                new LambdaQueryWrapper<MemoryMeta>()
                        .eq(MemoryMeta::getSourceHash, sourceHash)
                        .last("LIMIT 1"));
        if (byHash != null) {
            return byHash;
        }
        String legacyHash = computeLegacyMemoryHash(userId, characterId, summary);
        return memoryMetaMapper.selectOne(
                new LambdaQueryWrapper<MemoryMeta>()
                        .eq(MemoryMeta::getSourceHash, legacyHash)
                        .last("LIMIT 1"));
    }

    /** Preferred: SHA-256(sorted source_msg_ids)) per schema; falls back to legacy text hash when IDs empty. */
    private String resolveSourceHash(Long userId, Long characterId, List<Long> sourceIds, String summary) {
        if (sourceIds != null && !sourceIds.isEmpty()) {
            return computeSourceIdsHash(sourceIds);
        }
        return computeLegacyMemoryHash(userId, characterId, summary);
    }

    private String computeSourceIdsHash(List<Long> sourceIds) {
        List<Long> sorted = new ArrayList<>(sourceIds);
        Collections.sort(sorted);
        String joined = sorted.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse("");
        return sha256Hex(joined);
    }

    private String computeLegacyMemoryHash(Long userId, Long characterId, String summary) {
        return sha256Hex("u:" + userId + "|c:" + characterId + "|text:" + summary);
    }

    private String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    private List<Long> mergeSourceIds(List<Long> oldIds, List<Long> newIds) {
        Set<Long> merged = new LinkedHashSet<>();
        if (oldIds != null) {
            merged.addAll(oldIds);
        }
        if (newIds != null) {
            merged.addAll(newIds);
        }
        return new ArrayList<>(merged);
    }

    private BigDecimal toImportance(double importance) {
        double clamped = Math.max(0, Math.min(1, importance));
        return BigDecimal.valueOf(clamped).setScale(2, RoundingMode.HALF_UP);
    }

    private enum MemoryUpsertResult { CREATED, UPDATED, SKIPPED }

    public record MemorySummaryTask(Long conversationId, Long characterId,
                                     Long userId) implements Serializable {}
}
