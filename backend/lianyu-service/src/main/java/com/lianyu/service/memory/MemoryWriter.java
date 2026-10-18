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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
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
    private static final Duration DEBOUNCE_TTL = Duration.ofSeconds(30);
    private static final long RESCHEDULE_DELAY_SECONDS = 5L;

    private final MessageMapper messageMapper;
    private final MemoryMetaMapper memoryMetaMapper;
    private final RabbitTemplate rabbitTemplate;
    private final MemoryCacheService memoryCacheService;
    private final MemoryExtractionService memoryExtractionService;
    private final MemoryVectorStore memoryVectorStore;
    private final MemoryOutboxService memoryOutboxService;

    /** 可选：AGENT 模式下把「抽取 + 写入」整体交给 backend-agent（null => Java 原链路）。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.lianyu.service.agent.AgentMemoryPort agentMemoryPort;
    private final StringRedisTemplate redisTemplate;
    private final ScheduledExecutorService rescheduleExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "memory-summary-reschedule");
        t.setDaemon(true);
        return t;
    });

    public void enqueueSummary(Long conversationId, Long characterId, Long userId,
                               String provider, String model) {
        String debounceKey = debounceKey(conversationId, characterId);
        Boolean first = redisTemplate.opsForValue().setIfAbsent(debounceKey, "0", DEBOUNCE_TTL);
        if (Boolean.FALSE.equals(first)) {
            redisTemplate.opsForValue().set(debounceKey, "1", DEBOUNCE_TTL);
            log.debug("Memory summary debounce pending reschedule: conversationId={}, characterId={}",
                    conversationId, characterId);
            return;
        }
        sendSummaryTask(new MemorySummaryTask(conversationId, characterId, userId, provider, model));
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
            // Strangler: AGENT 模式下抽取与写入整体交给 Python（同一事务、同一哈希规则）
            if (agentMemoryPort != null && agentMemoryPort.extractAndWriteIfActive(
                    task.userId(), task.characterId(), toPortMessages(recentMsgs),
                    task.provider(), task.model())) {
                memoryCacheService.invalidate(task.userId(), task.characterId());
                return;
            }
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
                MemoryUpsertOutcome outcome = upsertTypedMemory(
                        task,
                        sourceIds,
                        memory.summary(),
                        memory.memoryType(),
                        memory.importance());
                switch (outcome.result()) {
                    case CREATED -> created++;
                    case UPDATED -> updated++;
                    case SKIPPED -> skipped++;
                }
                MemoryMeta saved = outcome.meta();
                if (saved != null
                        && saved.getId() != null
                        && (saved.getMilvusVecId() == null || saved.getMilvusVecId().isBlank())) {
                    // 向量投影交给 outbox relay（幂等重试），不在业务线程里等 Milvus
                    memoryOutboxService.enqueueUpsert(saved.getId());
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
        } finally {
            maybeReschedule(task);
        }
    }

    private void sendSummaryTask(MemorySummaryTask task) {
        rabbitTemplate.convertAndSend(EXCHANGE, ROUTING_KEY, task);
    }

    private java.util.List<com.lianyu.service.agent.AgentMemoryPort.AgentMemoryMessage> toPortMessages(
            java.util.List<com.lianyu.dao.entity.Message> messages) {
        java.util.List<com.lianyu.service.agent.AgentMemoryPort.AgentMemoryMessage> out = new java.util.ArrayList<>();
        for (com.lianyu.dao.entity.Message m : messages) {
            if (m != null && m.getContent() != null && !m.getContent().isBlank()) {
                out.add(new com.lianyu.service.agent.AgentMemoryPort.AgentMemoryMessage(
                        m.getId(), m.getRole(), m.getContent()));
            }
        }
        return out;
    }


    private void maybeReschedule(MemorySummaryTask task) {
        String debounceKey = debounceKey(task.conversationId(), task.characterId());
        String pending = redisTemplate.opsForValue().get(debounceKey);
        redisTemplate.delete(debounceKey);
        if (!"1".equals(pending)) {
            return;
        }
        rescheduleExecutor.schedule(
                () -> sendSummaryTask(new MemorySummaryTask(
                        task.conversationId(), task.characterId(), task.userId(),
                        task.provider(), task.model())),
                RESCHEDULE_DELAY_SECONDS,
                TimeUnit.SECONDS);
        log.info("Memory summary rescheduled: conversationId={}, characterId={}",
                task.conversationId(), task.characterId());
    }

    private static String debounceKey(Long conversationId, Long characterId) {
        return ENQUEUE_DEBOUNCE_PREFIX + conversationId + ":" + characterId;
    }

    /**
     * @deprecated 直接用 vecId 删除会绕过 outbox：Milvus 失败时删除意图永久丢失，
     * 留下「行已删、向量仍在」的幽灵记忆。新代码请用
     * {@link #deleteVectorForMemory(Long, String)}。
     */
    @Deprecated
    public void deleteVectors(List<String> vectorIds) {
        memoryVectorStore.delete(vectorIds);
    }

    /** 删除某条记忆的向量：只入队，由 relay 幂等执行（失败会重试）。 */
    public void deleteVectorForMemory(Long memoryId, String vecId) {
        memoryOutboxService.enqueueDelete(memoryId, vecId);
    }

    private MemoryUpsertOutcome upsertTypedMemory(MemorySummaryTask task,
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
                    return new MemoryUpsertOutcome(MemoryUpsertResult.SKIPPED, raced);
                }
                throw e;
            }

            // 只入队，不等 Milvus：投影失败由 relay 指数退避重试（审计 C2）
            memoryOutboxService.enqueueUpsert(meta.getId());
            return new MemoryUpsertOutcome(MemoryUpsertResult.CREATED, meta);
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
            return new MemoryUpsertOutcome(MemoryUpsertResult.SKIPPED, existing);
        }

        String oldVecId = existing.getMilvusVecId();
        existing.setSummary(summary);
        existing.setMemoryType(memoryType);
        existing.setImportance(importanceValue);
        existing.setSourceMsgIds(mergeSourceIds(existing.getSourceMsgIds(), sourceIds));
        existing.setSourceHash(sourceHash);
        // 向量已过期：先清空本地引用，由 relay 重新投影
        existing.setMilvusVecId(null);
        memoryMetaMapper.updateById(existing);

        memoryOutboxService.enqueueDelete(existing.getId(), oldVecId);
        memoryOutboxService.enqueueUpsert(existing.getId());
        return new MemoryUpsertOutcome(MemoryUpsertResult.UPDATED, existing);
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

    private record MemoryUpsertOutcome(MemoryUpsertResult result, MemoryMeta meta) {}

    /**
     * provider/model 为产生该任务的本回合所用文本模型；为空（旧在途消息等）时由提取器
     * 回落到用户最近更新的启用 vault。
     */
    public record MemorySummaryTask(Long conversationId, Long characterId,
                                     Long userId, String provider, String model) implements Serializable {}
}
