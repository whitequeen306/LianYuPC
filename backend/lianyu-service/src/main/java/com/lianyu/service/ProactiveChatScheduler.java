package com.lianyu.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lianyu.dao.entity.Character;
import com.lianyu.dao.entity.Conversation;
import com.lianyu.dao.entity.Message;
import com.lianyu.dao.mapper.CharacterMapper;
import com.lianyu.dao.mapper.ConversationMapper;
import com.lianyu.dao.mapper.MessageMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class ProactiveChatScheduler {

    private final ConversationMapper conversationMapper;
    private final MessageMapper messageMapper;
    private final CharacterMapper characterMapper;
    private final ConversationService conversationService;
    private final CharacterChatBehaviorResolver chatBehaviorResolver;
    private final StringRedisTemplate redisTemplate;

    @Value("${lianyu.chat.proactive.enabled:true}")
    private boolean proactiveEnabled;

    @Value("${lianyu.chat.proactive.max-conversations-per-run:1}")
    private int maxConversationsPerRun;

    @Value("${lianyu.chat.proactive.scan-limit:40}")
    private int scanLimit;

    @Scheduled(fixedDelayString = "${lianyu.chat.proactive.scan-interval-ms:45000}")
    public void tick() {
        if (!proactiveEnabled) {
            return;
        }

        List<Conversation> candidates = conversationMapper.selectList(
                new LambdaQueryWrapper<Conversation>()
                        .eq(Conversation::getMode, "SINGLE")
                        .orderByDesc(Conversation::getCreatedAt)
                        .last("LIMIT " + Math.max(1, scanLimit))
        );
        if (candidates.isEmpty()) {
            return;
        }

        Set<Long> characterIds = candidates.stream()
                .map(Conversation::getCharacterId)
                .filter(id -> id != null && id > 0)
                .collect(Collectors.toSet());
        Map<Long, Character> characterMap = new HashMap<>();
        if (!characterIds.isEmpty()) {
            characterMapper.selectByIds(characterIds).forEach(c -> characterMap.put(c.getId(), c));
        }

        List<Long> convIds = candidates.stream().map(Conversation::getId).toList();
        Map<Long, Message> latestMessageMap = messageMapper.selectLatestByConversationIds(convIds).stream()
                .filter(m -> m.getConversationId() != null)
                .collect(Collectors.toMap(Message::getConversationId, m -> m, (a, b) -> a));
        Map<Long, Message> latestUserMessageMap = messageMapper.selectLatestUserByConversationIds(convIds).stream()
                .filter(m -> m.getConversationId() != null)
                .collect(Collectors.toMap(Message::getConversationId, m -> m, (a, b) -> a));

        int sent = 0;
        for (Conversation conv : candidates) {
            if (sent >= Math.max(1, maxConversationsPerRun)) {
                break;
            }
            Character character = conv.getCharacterId() == null ? null : characterMap.get(conv.getCharacterId());
            CharacterChatBehavior behavior = chatBehaviorResolver.resolve(character);
            Message lastMessage = latestMessageMap.get(conv.getId());
            if (!shouldTriggerForConversation(conv, behavior, lastMessage)) {
                continue;
            }

            try {
                Message lastUser = latestUserMessageMap.get(conv.getId());
                if (lastUser == null) {
                    continue;
                }
                var replies = conversationService.sendProactiveMessage(
                        conv.getUserId(), conv.getId(), lastUser.getContent());
                if (!replies.isEmpty()) {
                    sent++;
                    setCooldown(conv.getId(), behavior);
                    log.info("Proactive chat sent: convId={}, character={}, style={}, pieces={}",
                            conv.getId(),
                            character != null ? character.getName() : "-",
                            behavior.speakingStyle(),
                            replies.size());
                }
            } catch (Exception e) {
                log.debug("Proactive chat skipped for convId={}, reason={}", conv.getId(), e.getMessage());
            }
        }
    }

    private boolean shouldTriggerForConversation(Conversation conv,
                                                 CharacterChatBehavior behavior,
                                                 Message lastMessage) {
        if (conv.getCharacterId() == null || !behavior.proactiveEnabled()) {
            return false;
        }
        String cooldownKey = cooldownKey(conv.getId());
        if (Boolean.TRUE.equals(redisTemplate.hasKey(cooldownKey))) {
            return false;
        }

        if (lastMessage == null) {
            return false;
        }
        if (!"USER".equalsIgnoreCase(lastMessage.getRole())) {
            return false;
        }

        LocalDateTime createdAt = lastMessage.getCreatedAt();
        if (createdAt == null) {
            return false;
        }
        if (createdAt.isAfter(LocalDateTime.now().minusMinutes(Math.max(1, behavior.minIdleMinutes())))) {
            return false;
        }

        return ThreadLocalRandom.current().nextDouble()
                <= Math.max(0.0, Math.min(1.0, behavior.triggerProbability()));
    }

    private void setCooldown(Long conversationId, CharacterChatBehavior behavior) {
        int min = Math.max(1, behavior.cooldownMinMinutes());
        int max = Math.max(min, behavior.cooldownMaxMinutes());
        int ttl = ThreadLocalRandom.current().nextInt(min, max + 1);
        redisTemplate.opsForValue().set(cooldownKey(conversationId), "1", Duration.ofMinutes(ttl));
    }

    private String cooldownKey(Long conversationId) {
        return "chat:proactive:cooldown:" + conversationId;
    }
}
