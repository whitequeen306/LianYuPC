package com.lianyu.service.conversation;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lianyu.dao.entity.Conversation;
import com.lianyu.dao.entity.Message;
import com.lianyu.dao.mapper.ConversationMapper;
import com.lianyu.dao.mapper.MessageMapper;
import cn.hutool.core.util.StrUtil;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class SessionSummaryService {

    static final String REDIS_KEY_PREFIX = "session:summary:";

    private final SessionSummaryProperties properties;
    private final SessionSummaryMerger merger;
    private final MessageMapper messageMapper;
    private final ConversationMapper conversationMapper;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public String formatForPrompt(Long conversationId) {
        if (!properties.isEnabled() || conversationId == null) {
            return null;
        }
        SessionSummaryState state = loadState(conversationId);
        if (state == null || StrUtil.isBlank(state.getText())) {
            return null;
        }
        return """

                
                === 本次对话 Earlier 摘要 ===
                """
                + state.getText().trim()
                + """
                
                
                说明：摘要描述本次聊天中较早的内容；与用户最近几条消息冲突时，以最近消息为准。""";
    }

    @Async
    public void maybeMergeAsync(Long conversationId) {
        maybeMerge(conversationId);
    }

    public void maybeMerge(Long conversationId) {
        if (!properties.isEnabled() || conversationId == null) {
            return;
        }
        Conversation conversation = conversationMapper.selectById(conversationId);
        if (conversation == null || !"SINGLE".equalsIgnoreCase(conversation.getMode())) {
            return;
        }
        Long userId = conversation.getUserId();
        if (userId == null) {
            return;
        }

        List<Message> recent = loadRecentMessages(conversationId, properties.getRawWindow());
        if (recent.isEmpty()) {
            return;
        }

        long windowOldestSeq = recent.get(0).getSeq() != null ? recent.get(0).getSeq() : 0L;
        SessionSummaryState state = loadState(conversationId);
        if (state == null) {
            state = new SessionSummaryState("", 0L, null);
        }

        List<Message> pending = loadPendingMessages(conversationId, state.getLastSummarizedSeq(), windowOldestSeq);
        if (!shouldMerge(pending)) {
            return;
        }

        String merged = merger.merge(userId, state.getText(), pending);
        if (StrUtil.isBlank(merged)) {
            return;
        }

        long newLastSeq = pending.stream()
                .map(Message::getSeq)
                .filter(seq -> seq != null)
                .max(Long::compareTo)
                .orElse(state.getLastSummarizedSeq());

        SessionSummaryState next = new SessionSummaryState(merged, newLastSeq, LocalDateTime.now());
        saveState(conversationId, next);
        log.info("Session summary updated: convId={}, pendingCount={}, chars={}, lastSeq={}",
                conversationId, pending.size(), merged.length(), newLastSeq);
    }

    public void invalidate(Long conversationId) {
        if (conversationId == null) {
            return;
        }
        redisTemplate.delete(redisKey(conversationId));
    }

    SessionSummaryState loadState(Long conversationId) {
        try {
            String json = redisTemplate.opsForValue().get(redisKey(conversationId));
            if (StrUtil.isBlank(json)) {
                return new SessionSummaryState("", 0L, null);
            }
            return objectMapper.readValue(json, SessionSummaryState.class);
        } catch (Exception e) {
            log.debug("Session summary read failed convId={}: {}", conversationId, e.getMessage());
            return new SessionSummaryState("", 0L, null);
        }
    }

    boolean shouldMerge(List<Message> pending) {
        if (pending == null || pending.isEmpty()) {
            return false;
        }
        if (pending.size() >= properties.getSlideBatchMin()) {
            return true;
        }
        LocalDateTime oldestCreated = pending.get(0).getCreatedAt();
        if (oldestCreated == null) {
            return false;
        }
        return oldestCreated.isBefore(LocalDateTime.now().minusMinutes(Math.max(1, properties.getStaleMinutes())));
    }

    List<Message> loadPendingMessages(Long conversationId, long lastSummarizedSeq, long windowOldestSeq) {
        if (windowOldestSeq <= lastSummarizedSeq) {
            return List.of();
        }
        return messageMapper.selectList(new LambdaQueryWrapper<Message>()
                .eq(Message::getConversationId, conversationId)
                .gt(Message::getSeq, lastSummarizedSeq)
                .lt(Message::getSeq, windowOldestSeq)
                .orderByAsc(Message::getSeq));
    }

    List<Message> loadRecentMessages(Long conversationId, int limit) {
        int safeLimit = Math.max(1, limit);
        List<Message> messages = messageMapper.selectList(new LambdaQueryWrapper<Message>()
                .eq(Message::getConversationId, conversationId)
                .orderByDesc(Message::getSeq)
                .last("LIMIT " + safeLimit));
        if (messages.isEmpty()) {
            return List.of();
        }
        Collections.reverse(messages);
        return messages;
    }

    private void saveState(Long conversationId, SessionSummaryState state) {
        try {
            String json = objectMapper.writeValueAsString(state);
            redisTemplate.opsForValue().set(
                    redisKey(conversationId),
                    json,
                    Duration.ofHours(Math.max(1, properties.getRedisTtlHours())));
        } catch (Exception e) {
            log.warn("Session summary save failed convId={}: {}", conversationId, e.getMessage());
        }
    }

    private static String redisKey(Long conversationId) {
        return REDIS_KEY_PREFIX + conversationId;
    }
}
