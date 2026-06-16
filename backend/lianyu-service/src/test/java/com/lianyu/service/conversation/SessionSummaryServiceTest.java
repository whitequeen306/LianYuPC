package com.lianyu.service.conversation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.lianyu.dao.entity.Conversation;
import com.lianyu.dao.entity.Message;
import com.lianyu.dao.mapper.ConversationMapper;
import com.lianyu.dao.mapper.MessageMapper;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@ExtendWith(MockitoExtension.class)
class SessionSummaryServiceTest {

    @Mock
    private SessionSummaryMerger merger;
    @Mock
    private MessageMapper messageMapper;
    @Mock
    private ConversationMapper conversationMapper;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;

    private SessionSummaryProperties properties;
    private ObjectMapper objectMapper;
    private SessionSummaryService service;

    @BeforeEach
    void setUp() {
        properties = new SessionSummaryProperties();
        properties.setEnabled(true);
        properties.setRawWindow(32);
        properties.setSlideBatchMin(6);
        properties.setStaleMinutes(30);
        properties.setRedisTtlHours(72);
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        service = new SessionSummaryService(
                properties, merger, messageMapper, conversationMapper, redisTemplate, objectMapper);
    }

    @Test
    void loadPendingMessagesRespectsWindowBoundary() {
        when(messageMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
                msg(21L, "USER", "a"),
                msg(22L, "ASSISTANT", "b")));

        List<Message> pending = service.loadPendingMessages(9L, 20L, 27L);

        assertEquals(2, pending.size());
        assertEquals(21L, pending.get(0).getSeq());
    }

    @Test
    void shouldMergeWhenPendingCountMeetsBatchThreshold() {
        List<Message> pending = List.of(
                msg(1L, "USER", "1"),
                msg(2L, "USER", "2"),
                msg(3L, "USER", "3"),
                msg(4L, "USER", "4"),
                msg(5L, "USER", "5"),
                msg(6L, "USER", "6"));
        assertTrue(service.shouldMerge(pending));
    }

    @Test
    void shouldMergeWhenPendingIsStaleEvenIfBelowBatch() {
        Message stale = msg(1L, "USER", "old");
        stale.setCreatedAt(LocalDateTime.now().minusMinutes(45));
        assertTrue(service.shouldMerge(List.of(stale)));
    }

    @Test
    void shouldNotMergeWhenPendingTooSmallAndFresh() {
        Message fresh = msg(1L, "USER", "new");
        fresh.setCreatedAt(LocalDateTime.now().minusMinutes(5));
        assertFalse(service.shouldMerge(List.of(fresh)));
    }

    @Test
    void maybeMergeSkipsWhenPendingBelowThreshold() {
        Conversation conversation = singleConversation(11L, 3L);
        when(conversationMapper.selectById(11L)).thenReturn(conversation);
        when(messageMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(recentWindow(30L))
                .thenReturn(List.of(msg(25L, "USER", "only one")));

        service.maybeMerge(11L);

        verify(merger, never()).merge(anyLong(), anyString(), any());
    }

    @Test
    void maybeMergePersistsMergedSummary() throws Exception {
        Conversation conversation = singleConversation(11L, 3L);
        when(conversationMapper.selectById(11L)).thenReturn(conversation);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(SessionSummaryService.REDIS_KEY_PREFIX + "11")).thenReturn(null);
        when(messageMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(recentWindow(40L))
                .thenReturn(List.of(
                        msg(31L, "USER", "u1"),
                        msg(32L, "USER", "u2"),
                        msg(33L, "USER", "u3"),
                        msg(34L, "USER", "u4"),
                        msg(35L, "USER", "u5"),
                        msg(36L, "USER", "u6")));
        when(merger.merge(eq(3L), eq(""), any())).thenReturn("【约定/计划】下午买衣服");

        service.maybeMerge(11L);

        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(
                eq(SessionSummaryService.REDIS_KEY_PREFIX + "11"),
                jsonCaptor.capture(),
                eq(Duration.ofHours(72)));
        SessionSummaryState saved = objectMapper.readValue(jsonCaptor.getValue(), SessionSummaryState.class);
        assertEquals("【约定/计划】下午买衣服", saved.getText());
        assertEquals(36L, saved.getLastSummarizedSeq());
        assertNotNull(saved.getUpdatedAt());
    }

    @Test
    void formatForPromptReturnsNullWhenEmpty() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(SessionSummaryService.REDIS_KEY_PREFIX + "5")).thenReturn(null);
        assertNull(service.formatForPrompt(5L));
    }

    @Test
    void mergerEnforceHardMaxDropsSectionsInPriorityOrder() {
        SessionSummaryMerger hardMerger = new SessionSummaryMerger(null, properties);
        properties.setHardMaxChars(80);
        String longSummary = """
                【约定/计划】
                下午三点一起去商场买衣服，别迟到
                
                【正在聊的话题】
                在讨论电影和周末安排，内容比较长需要被压缩
                
                【用户状态】
                用户有点累，想先休息一会再聊别的
                """;

        String trimmed = hardMerger.enforceHardMax(longSummary);

        assertTrue(trimmed.length() <= properties.getHardMaxChars());
        assertTrue(trimmed.contains("【约定/计划】"));
        assertFalse(trimmed.contains("【用户状态】"));
    }

    private static Conversation singleConversation(Long id, Long userId) {
        Conversation conversation = new Conversation();
        conversation.setId(id);
        conversation.setUserId(userId);
        conversation.setMode("SINGLE");
        return conversation;
    }

    private static List<Message> recentWindow(long oldestSeq) {
        Message oldest = msg(oldestSeq, "USER", "window start");
        return List.of(oldest);
    }

    private static Message msg(long seq, String role, String content) {
        Message message = new Message();
        message.setSeq(seq);
        message.setRole(role);
        message.setContent(content);
        message.setCreatedAt(LocalDateTime.now());
        return message;
    }
}
