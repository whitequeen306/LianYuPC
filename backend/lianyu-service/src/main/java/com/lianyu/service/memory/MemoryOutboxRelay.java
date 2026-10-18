package com.lianyu.service.memory;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.lianyu.dao.entity.MemoryMeta;
import com.lianyu.dao.entity.MemoryOutbox;
import com.lianyu.dao.mapper.MemoryMetaMapper;
import com.lianyu.dao.mapper.MemoryOutboxMapper;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * outbox → Milvus 的幂等投影器。
 *
 * <p>设计要点：
 * <ul>
 *   <li><b>不在事务里做网络调用</b>：逐行处理，每行自己的状态更新独立提交，
 *       Milvus 慢/挂不会拖住业务事务（红线 §8.1）。</li>
 *   <li><b>先认领再执行</b>：UPDATE status=PENDING→RUNNING 且检查影响行数，
 *       多实例部署时同一行只会被一个实例处理。</li>
 *   <li><b>指数退避</b>：失败 attempts+1，下次重试时间 2^attempts 秒（上限 1 小时），
 *       超过 {@code max-attempts} 置 DEAD 并留 last_error，避免无限重试打爆 Milvus。</li>
 *   <li><b>幂等</b>：UPSERT 总是「删旧向量 → 写新向量 → 回填 vec_id」，
 *       重复执行只会覆盖，不会产生重复向量。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryOutboxRelay {

    private final MemoryOutboxMapper outboxMapper;
    private final MemoryMetaMapper memoryMetaMapper;
    private final MemoryVectorStore memoryVectorStore;

    @Value("${lianyu.memory.outbox.enabled:true}")
    private boolean enabled;

    @Value("${lianyu.memory.outbox.batch-size:20}")
    private int batchSize;

    @Value("${lianyu.memory.outbox.max-attempts:8}")
    private int maxAttempts;

    @Scheduled(fixedDelayString = "${lianyu.memory.outbox.relay-interval-ms:5000}",
            initialDelayString = "${lianyu.memory.outbox.relay-initial-delay-ms:30000}")
    public void relay() {
        if (!enabled) {
            return;
        }
        int processed = drainOnce();
        if (processed > 0) {
            log.debug("memory outbox relay processed {} rows", processed);
        }
    }

    /**
     * 处理一批到期任务。
     *
     * @return 实际处理（无论成功失败）的行数
     */
    public int drainOnce() {
        List<MemoryOutbox> batch = outboxMapper.selectList(
                new LambdaQueryWrapper<MemoryOutbox>()
                        .eq(MemoryOutbox::getStatus, MemoryOutbox.STATUS_PENDING)
                        .le(MemoryOutbox::getNextAttemptAt, LocalDateTime.now())
                        .orderByAsc(MemoryOutbox::getId)
                        .last("LIMIT " + Math.max(1, batchSize)));
        int done = 0;
        for (MemoryOutbox row : batch) {
            if (claim(row.getId())) {
                handle(row);
                done++;
            }
        }
        return done;
    }

    /** 认领：PENDING → RUNNING。影响行数为 0 说明别的实例抢先处理了。 */
    private boolean claim(Long id) {
        int affected = outboxMapper.update(null,
                new LambdaUpdateWrapper<MemoryOutbox>()
                        .eq(MemoryOutbox::getId, id)
                        .eq(MemoryOutbox::getStatus, MemoryOutbox.STATUS_PENDING)
                        .set(MemoryOutbox::getStatus, MemoryOutbox.STATUS_RUNNING));
        return affected > 0;
    }

    private void handle(MemoryOutbox row) {
        try {
            if (MemoryOutbox.OP_DELETE.equals(row.getOp())) {
                if (row.getVecId() != null && !row.getVecId().isBlank()) {
                    memoryVectorStore.delete(List.of(row.getVecId()));
                }
                markDone(row.getId());
                return;
            }
            projectUpsert(row.getMemoryId(), row.getId());
        } catch (Exception e) {
            markFailed(row, e);
        }
    }

    private void projectUpsert(Long memoryId, Long outboxId) {
        MemoryMeta meta = memoryMetaMapper.selectById(memoryId);
        if (meta == null || meta.getSummary() == null || meta.getSummary().isBlank()) {
            // 记忆已被删除或为空：没有可投影的内容，视为完成（对应的 DELETE 会另行入队）
            log.debug("memory outbox skip: memory gone or blank, memoryId={}", memoryId);
            markDone(outboxId);
            return;
        }
        String oldVecId = meta.getMilvusVecId();
        if (oldVecId != null && !oldVecId.isBlank()) {
            memoryVectorStore.delete(List.of(oldVecId));
        }
        String vecId = memoryVectorStore.insert(
                meta.getCharacterId(), meta.getUserId(), meta.getId(),
                meta.getSummary(), meta.getMemoryType());
        if (vecId == null) {
            throw new IllegalStateException("milvus insert returned null for memoryId=" + memoryId);
        }
        meta.setMilvusVecId(vecId);
        memoryMetaMapper.updateById(meta);
        markDone(outboxId);
    }

    private void markDone(Long id) {
        outboxMapper.update(null,
                new LambdaUpdateWrapper<MemoryOutbox>()
                        .eq(MemoryOutbox::getId, id)
                        .set(MemoryOutbox::getStatus, MemoryOutbox.STATUS_DONE)
                        .set(MemoryOutbox::getLastError, null));
    }

    private void markFailed(MemoryOutbox row, Exception e) {
        int attempts = (row.getAttempts() == null ? 0 : row.getAttempts()) + 1;
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        if (attempts >= Math.max(1, maxAttempts)) {
            outboxMapper.update(null,
                    new LambdaUpdateWrapper<MemoryOutbox>()
                            .eq(MemoryOutbox::getId, row.getId())
                            .set(MemoryOutbox::getStatus, MemoryOutbox.STATUS_DEAD)
                            .set(MemoryOutbox::getAttempts, attempts)
                            .set(MemoryOutbox::getLastError, truncate(message)));
            log.error("memory outbox DEAD after {} attempts: id={}, memoryId={}, op={}, reason={}",
                    attempts, row.getId(), row.getMemoryId(), row.getOp(), message);
            return;
        }
        long backoffSeconds = Math.min(3600L, 1L << Math.min(attempts, 12));  // 2,4,8,...上限 1h
        outboxMapper.update(null,
                new LambdaUpdateWrapper<MemoryOutbox>()
                        .eq(MemoryOutbox::getId, row.getId())
                        .set(MemoryOutbox::getStatus, MemoryOutbox.STATUS_PENDING)
                        .set(MemoryOutbox::getAttempts, attempts)
                        .set(MemoryOutbox::getNextAttemptAt, LocalDateTime.now().plusSeconds(backoffSeconds))
                        .set(MemoryOutbox::getLastError, truncate(message)));
        log.warn("memory outbox retry scheduled in {}s: id={}, memoryId={}, attempts={}, reason={}",
                backoffSeconds, row.getId(), row.getMemoryId(), attempts, message);
    }

    /** 手动重放（运维/测试用）：把 DEAD 或卡在 RUNNING 的行重新排队。 */
    public int requeueStuck(Duration olderThan) {
        LocalDateTime threshold = LocalDateTime.now().minus(olderThan);
        return outboxMapper.update(null,
                new LambdaUpdateWrapper<MemoryOutbox>()
                        .in(MemoryOutbox::getStatus,
                                MemoryOutbox.STATUS_DEAD, MemoryOutbox.STATUS_RUNNING)
                        .le(MemoryOutbox::getUpdatedAt, threshold)
                        .set(MemoryOutbox::getStatus, MemoryOutbox.STATUS_PENDING)
                        .set(MemoryOutbox::getAttempts, 0)
                        .set(MemoryOutbox::getNextAttemptAt, LocalDateTime.now()));
    }

    private static String truncate(String message) {
        return message.length() <= 480 ? message : message.substring(0, 480);
    }
}
