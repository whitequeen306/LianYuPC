package com.lianyu.service.conversation;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lianyu.dao.dto.ConversationUserMessageCountRow;
import com.lianyu.dao.entity.Character;
import com.lianyu.dao.entity.Conversation;
import com.lianyu.dao.entity.Message;
import com.lianyu.dao.mapper.CharacterMapper;
import com.lianyu.dao.mapper.ConversationMapper;
import com.lianyu.dao.mapper.MessageMapper;
import com.lianyu.service.character.CharacterChatBehavior;
import com.lianyu.service.character.CharacterChatBehaviorResolver;
import com.lianyu.service.relationship.RelationshipPhase;
import com.lianyu.service.relationship.RelationshipSnapshot;
import com.lianyu.service.relationship.RelationshipStateService;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ProactiveChatScheduler {

    private final ConversationMapper conversationMapper;
    private final MessageMapper messageMapper;
    private final CharacterMapper characterMapper;
    private final ConversationService conversationService;
    private final CharacterChatBehaviorResolver chatBehaviorResolver;
    private final EngagementFrequencyScorer engagementScorer;
    private final StringRedisTemplate redisTemplate;
    private final RelationshipStateService relationshipStateService;

    @Value("${lianyu.chat.proactive.enabled:true}")
    private boolean proactiveEnabled;

    @Value("${lianyu.chat.proactive.max-conversations-per-run:2}")
    private int maxConversationsPerRun;

    @Value("${lianyu.chat.proactive.scan-limit:40}")
    private int scanLimit;

    @Scheduled(fixedDelayString = "${lianyu.chat.proactive.scan-interval-ms:30000}")
    public void tick() {
        if (!proactiveEnabled) {
            return;
        }

        List<Conversation> candidates = conversationMapper.selectList(
                new LambdaQueryWrapper<Conversation>()
                        .eq(Conversation::getMode, "SINGLE")
                        .isNotNull(Conversation::getCharacterId)
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

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime activitySince = now.minusDays(engagementScorer.proactiveActivityWindowDays());
        Map<Long, Long> userMsgCountMap = loadUserMessageCounts(convIds, activitySince);

        List<ScoredCandidate> scored = new ArrayList<>();
        for (Conversation conv : candidates) {
            Character character = characterMap.get(conv.getCharacterId());
            CharacterChatBehavior behavior = chatBehaviorResolver.resolve(character);
            Message lastMessage = latestMessageMap.get(conv.getId());
            Message lastUser = latestUserMessageMap.get(conv.getId());
            if (!isEligible(conv, behavior, lastMessage, lastUser)) {
                continue;
            }
            int userMsgs = userMsgCountMap.getOrDefault(conv.getId(), 0L).intValue();
            LocalDateTime lastActivityAt = lastMessage.getCreatedAt();
            double effectiveProb = engagementScorer.proactiveTriggerProbability(
                    behavior, userMsgs, lastActivityAt, now);
            long idleMinutes = lastActivityAt == null ? 0 : ChronoUnit.MINUTES.between(lastActivityAt, now);
            if (idleMinutes >= behavior.minIdleMinutes()) {
                effectiveProb = Math.max(effectiveProb, behavior.triggerProbability());
            }
            if (effectiveProb <= 0.0) {
                continue;
            }
            Message contextMessage = lastUser != null ? lastUser : lastMessage;
            scored.add(new ScoredCandidate(conv, character, behavior, contextMessage, effectiveProb));
        }

        if (scored.isEmpty()) {
            return;
        }

        scored.sort(Comparator.comparingDouble(ScoredCandidate::effectiveProb).reversed());

        int sent = 0;
        int quota = Math.max(1, maxConversationsPerRun);
        for (ScoredCandidate item : scored) {
            if (sent >= quota) {
                break;
            }
            if (ThreadLocalRandom.current().nextDouble() > item.effectiveProb()) {
                continue;
            }
            try {
                var replies = conversationService.sendProactiveMessage(
                        item.conv().getUserId(),
                        item.conv().getId(),
                        item.lastUser().getContent());
                if (!replies.isEmpty()) {
                    sent++;
                    setCooldown(item.conv().getId(), item.behavior());
                    log.info("Proactive chat sent: convId={}, character={}, style={}, score={}, pieces={}",
                            item.conv().getId(),
                            item.character() != null ? item.character().getName() : "-",
                            item.behavior().speakingStyle(),
                            String.format("%.2f", item.effectiveProb()),
                            replies.size());
                }
            } catch (Exception e) {
                log.debug("Proactive chat skipped for convId={}, reason={}", item.conv().getId(), e.getMessage());
            }
        }
    }

    private Map<Long, Long> loadUserMessageCounts(List<Long> convIds, LocalDateTime since) {
        if (convIds == null || convIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Long> map = new HashMap<>();
        List<ConversationUserMessageCountRow> rows =
                messageMapper.selectUserMessageCountsSince(convIds, since);
        for (ConversationUserMessageCountRow row : rows) {
            if (row.getConversationId() != null) {
                map.put(row.getConversationId(), row.getTotal() == null ? 0L : row.getTotal());
            }
        }
        return map;
    }

    private boolean isEligible(Conversation conv,
                               CharacterChatBehavior behavior,
                               Message lastMessage,
                               Message lastUser) {
        if (conv.getCharacterId() == null || !behavior.proactiveEnabled()) {
            return false;
        }
        RelationshipSnapshot snapshot = relationshipStateService.getSnapshot(conv.getUserId(), conv.getCharacterId());
        if (snapshot.phase() == RelationshipPhase.INJURED) {
            return false;
        }
        if (Boolean.TRUE.equals(redisTemplate.hasKey(cooldownKey(conv.getId())))) {
            return false;
        }
        if (lastMessage == null) {
            return false;
        }
        LocalDateTime lastActivityAt = lastMessage.getCreatedAt();
        if (lastActivityAt == null) {
            return false;
        }
        return !lastActivityAt.isAfter(LocalDateTime.now().minusMinutes(Math.max(1, behavior.minIdleMinutes())));
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

    private record ScoredCandidate(
            Conversation conv,
            Character character,
            CharacterChatBehavior behavior,
            Message lastUser,
            double effectiveProb) {
    }
}
