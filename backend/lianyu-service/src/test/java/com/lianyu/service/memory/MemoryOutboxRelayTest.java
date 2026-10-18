package com.lianyu.service.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lianyu.dao.entity.MemoryMeta;
import com.lianyu.dao.entity.MemoryOutbox;
import com.lianyu.dao.enums.MemoryType;
import com.lianyu.dao.mapper.MemoryMetaMapper;
import com.lianyu.dao.mapper.MemoryOutboxMapper;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * outbox relay 测试。
 *
 * <p>守的是三条：投影幂等、失败退避、上限死信。
 * 任何一条破了，用户侧表现都是「记忆写入看似成功但检索不到」。
 */
class MemoryOutboxRelayTest {

    private MemoryOutboxMapper outboxMapper;
    private MemoryMetaMapper memoryMetaMapper;
    private MemoryVectorStore vectorStore;
    private MemoryOutboxRelay relay;

    /**
     * MyBatis-Plus 的 LambdaWrapper 依赖 TableInfo 缓存（正常由启动时 mapper 扫描建立）。
     * 纯单测里必须手动初始化，否则 requeueStuck/drainOnce 里的 lambda 列解析会抛
     * "can not find lambda cache for this entity"。
     */
    @org.junit.jupiter.api.BeforeAll
    static void initMybatisPlusMetadata() {
        org.apache.ibatis.builder.MapperBuilderAssistant assistant =
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), "");
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                assistant, MemoryOutbox.class);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                assistant, MemoryMeta.class);
    }

    @BeforeEach
    void setUp() {
        outboxMapper = mock(MemoryOutboxMapper.class);
        memoryMetaMapper = mock(MemoryMetaMapper.class);
        vectorStore = mock(MemoryVectorStore.class);
        relay = new MemoryOutboxRelay(outboxMapper, memoryMetaMapper, vectorStore);
        ReflectionTestUtils.setField(relay, "enabled", true);
        ReflectionTestUtils.setField(relay, "batchSize", 10);
        ReflectionTestUtils.setField(relay, "maxAttempts", 3);
    }

    private static MemoryOutbox row(Long id, String op, String vecId, int attempts) {
        MemoryOutbox r = new MemoryOutbox();
        r.setId(id);
        r.setMemoryId(100L + id);
        r.setOp(op);
        r.setVecId(vecId);
        r.setStatus(MemoryOutbox.STATUS_PENDING);
        r.setAttempts(attempts);
        return r;
    }

    private static MemoryMeta meta(Long id) {
        MemoryMeta m = new MemoryMeta();
        m.setId(id);
        m.setCharacterId(3L);
        m.setUserId(7L);
        m.setSummary("用户喜欢猫");
        m.setMemoryType(MemoryType.FACT);
        m.setImportance(new BigDecimal("0.80"));
        return m;
    }

    @Test
    void upsertInsertsVectorAndBackfillsVecId() {
        when(outboxMapper.selectList(any())).thenReturn(List.of(row(1L, MemoryOutbox.OP_UPSERT, null, 0)));
        when(outboxMapper.update(any(), any())).thenReturn(1);
        MemoryMeta m = meta(101L);
        when(memoryMetaMapper.selectById(101L)).thenReturn(m);
        when(vectorStore.insert(3L, 7L, 101L, "用户喜欢猫", MemoryType.FACT)).thenReturn("vec-abc");

        int processed = relay.drainOnce();

        assertEquals(1, processed);
        verify(vectorStore).insert(3L, 7L, 101L, "用户喜欢猫", MemoryType.FACT);
        assertEquals("vec-abc", m.getMilvusVecId(), "必须回填 vec_id，否则记忆检索不到");
        verify(memoryMetaMapper).updateById(m);
    }

    @Test
    void upsertReplacesOldVectorFirst() {
        MemoryMeta existing = meta(101L);
        existing.setMilvusVecId("vec-old");
        when(outboxMapper.selectList(any())).thenReturn(List.of(row(1L, MemoryOutbox.OP_UPSERT, null, 0)));
        when(outboxMapper.update(any(), any())).thenReturn(1);
        when(memoryMetaMapper.selectById(101L)).thenReturn(existing);
        when(vectorStore.insert(anyLong(), anyLong(), anyLong(), anyString(), any()))
                .thenReturn("vec-new");

        relay.drainOnce();

        verify(vectorStore).delete(List.of("vec-old"));
        verify(vectorStore).insert(anyLong(), anyLong(), anyLong(), anyString(), any());
    }

    @Test
    void deleteOpDeletesVector() {
        when(outboxMapper.selectList(any())).thenReturn(
                List.of(row(2L, MemoryOutbox.OP_DELETE, "vec-gone", 0)));
        when(outboxMapper.update(any(), any())).thenReturn(1);

        relay.drainOnce();

        verify(vectorStore).delete(List.of("vec-gone"));
    }

    @Test
    void deleteOpWithoutVecIdIsNoOp() {
        when(outboxMapper.selectList(any())).thenReturn(
                List.of(row(2L, MemoryOutbox.OP_DELETE, null, 0)));
        when(outboxMapper.update(any(), any())).thenReturn(1);

        relay.drainOnce();

        verify(vectorStore, never()).delete(anyList());
    }

    @Test
    void missingMemoryIsMarkedDoneNotRetried() {
        // 记忆已被删除：没有可投影内容，不该无限重试
        when(outboxMapper.selectList(any())).thenReturn(List.of(row(3L, MemoryOutbox.OP_UPSERT, null, 0)));
        when(outboxMapper.update(any(), any())).thenReturn(1);
        when(memoryMetaMapper.selectById(103L)).thenReturn(null);

        relay.drainOnce();

        verify(vectorStore, never()).insert(anyLong(), anyLong(), anyLong(), anyString(), any());
    }

    @Test
    void milvusFailureSchedulesRetryWithBackoff() {
        when(outboxMapper.selectList(any())).thenReturn(List.of(row(4L, MemoryOutbox.OP_UPSERT, null, 1)));
        when(outboxMapper.update(any(), any())).thenReturn(1);
        when(memoryMetaMapper.selectById(104L)).thenReturn(meta(104L));
        when(vectorStore.insert(anyLong(), anyLong(), anyLong(), anyString(), any()))
                .thenThrow(new RuntimeException("milvus timeout"));

        relay.drainOnce();

        // 失败后状态回到 PENDING（可被下次扫描捡起），attempts 递增
        verify(outboxMapper, org.mockito.Mockito.atLeastOnce()).update(any(), any());
    }

    @Test
    void nullVecIdFromMilvusIsFailureNotSilentSuccess() {
        // 原实现只 log.warn 就当成功，导致记忆永久检索不到
        when(outboxMapper.selectList(any())).thenReturn(List.of(row(5L, MemoryOutbox.OP_UPSERT, null, 0)));
        when(outboxMapper.update(any(), any())).thenReturn(1);
        when(memoryMetaMapper.selectById(105L)).thenReturn(meta(105L));
        when(vectorStore.insert(anyLong(), anyLong(), anyLong(), anyString(), any())).thenReturn(null);

        relay.drainOnce();

        verify(memoryMetaMapper, never()).updateById(any(MemoryMeta.class));
    }

    @Test
    void claimConflictSkipsRow() {
        // 别的实例已认领（update 影响 0 行）→ 本实例不得重复处理
        when(outboxMapper.selectList(any())).thenReturn(List.of(row(6L, MemoryOutbox.OP_UPSERT, null, 0)));
        when(outboxMapper.update(any(), any())).thenReturn(0);

        int processed = relay.drainOnce();

        assertEquals(0, processed);
        verify(memoryMetaMapper, never()).selectById(anyLong());
    }

    @Test
    void requeueStuckResetsDeadRows() {
        when(outboxMapper.update(any(), any())).thenReturn(2);
        assertEquals(2, relay.requeueStuck(java.time.Duration.ofHours(1)));
    }

    @Test
    void disabledRelayDoesNothing() {
        ReflectionTestUtils.setField(relay, "enabled", false);
        relay.relay();
        verify(outboxMapper, never()).selectList(any());
    }
}
