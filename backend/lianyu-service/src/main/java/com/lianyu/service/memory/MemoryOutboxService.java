package com.lianyu.service.memory;

import com.lianyu.dao.entity.MemoryOutbox;
import com.lianyu.dao.mapper.MemoryOutboxMapper;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 记忆 outbox 入队（在调用方事务内执行，只写本地表，无网络调用）。
 *
 * <p>为什么必须同事务：memory_meta 写入与投影意图必须原子落库，
 * 否则「写了记忆但从未投影」或「投影了但记忆回滚」都会发生。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryOutboxService {

    private final MemoryOutboxMapper memoryOutboxMapper;

    /** 待投影（新增或内容变更）。 */
    public void enqueueUpsert(Long memoryId) {
        if (memoryId == null) {
            return;
        }
        enqueue(memoryId, MemoryOutbox.OP_UPSERT, null);
    }

    /** 待删除旧向量（改内容时替换、或记忆被删）。 */
    public void enqueueDelete(Long memoryId, String vecId) {
        if (memoryId == null || vecId == null || vecId.isBlank()) {
            return;
        }
        enqueue(memoryId, MemoryOutbox.OP_DELETE, vecId);
    }

    private void enqueue(Long memoryId, String op, String vecId) {
        MemoryOutbox row = new MemoryOutbox();
        row.setMemoryId(memoryId);
        row.setOp(op);
        row.setVecId(vecId);
        row.setStatus(MemoryOutbox.STATUS_PENDING);
        row.setAttempts(0);
        row.setNextAttemptAt(LocalDateTime.now());
        memoryOutboxMapper.insert(row);
        log.debug("memory outbox enqueued: memoryId={}, op={}, vecId={}", memoryId, op, vecId);
    }
}
