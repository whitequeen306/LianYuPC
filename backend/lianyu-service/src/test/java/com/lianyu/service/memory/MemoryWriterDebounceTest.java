package com.lianyu.service.memory;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lianyu.dao.mapper.MemoryMetaMapper;
import com.lianyu.dao.mapper.MessageMapper;
import com.lianyu.service.memory.MemoryWriter.MemorySummaryTask;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

class MemoryWriterDebounceTest {

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOps;
    private RabbitTemplate rabbitTemplate;
    private MessageMapper messageMapper;
    private MemoryWriter writer;

    @BeforeEach
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        rabbitTemplate = mock(RabbitTemplate.class);
        messageMapper = mock(MessageMapper.class);
        when(messageMapper.selectList(any())).thenReturn(List.of());

        writer = new MemoryWriter(
                messageMapper,
                mock(MemoryMetaMapper.class),
                rabbitTemplate,
                mock(MemoryCacheService.class),
                mock(MemoryExtractionService.class),
                mock(MemoryVectorStore.class),
                mock(MemoryMilvusSyncService.class),
                redisTemplate);
    }

    @Test
    void enqueueSummary_secondCallWithinWindowMarksPending() {
        when(valueOps.setIfAbsent(anyString(), eq("0"), eq(Duration.ofSeconds(30)))).thenReturn(true, false);

        writer.enqueueSummary(1L, 2L, 3L, "p", null);
        writer.enqueueSummary(1L, 2L, 3L, "p", null);

        verify(valueOps, times(2)).setIfAbsent("memory:summary:debounce:1:2", "0", Duration.ofSeconds(30));
        verify(valueOps).set("memory:summary:debounce:1:2", "1", Duration.ofSeconds(30));
        verify(rabbitTemplate, times(1)).convertAndSend(anyString(), anyString(), any(MemorySummaryTask.class));
    }

    @Test
    void maybeReschedule_pendingFlagSendsFollowUpTask() throws Exception {
        MemorySummaryTask task = new MemorySummaryTask(1L, 2L, 3L, "p", null);
        when(valueOps.get("memory:summary:debounce:1:2")).thenReturn("1");

        ReflectionTestUtils.invokeMethod(writer, "maybeReschedule", task);

        verify(redisTemplate).delete("memory:summary:debounce:1:2");
        Thread.sleep(5200);
        verify(rabbitTemplate, times(1)).convertAndSend(anyString(), anyString(), any(MemorySummaryTask.class));
    }

    @Test
    void maybeReschedule_withoutPending_doesNotSendFollowUpTask() {
        MemorySummaryTask task = new MemorySummaryTask(9L, 8L, 7L, "p", null);
        when(valueOps.get("memory:summary:debounce:9:8")).thenReturn("0");

        ReflectionTestUtils.invokeMethod(writer, "maybeReschedule", task);

        verify(redisTemplate).delete("memory:summary:debounce:9:8");
        verify(rabbitTemplate, never()).convertAndSend(anyString(), anyString(), any(MemorySummaryTask.class));
    }
}
