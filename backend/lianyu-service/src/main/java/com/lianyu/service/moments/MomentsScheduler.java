package com.lianyu.service.moments;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lianyu.dao.dto.ConversationUserMessageCountRow;
import com.lianyu.dao.dto.MomentsDailyCountRow;
import com.lianyu.dao.entity.Character;
import com.lianyu.dao.entity.Conversation;
import com.lianyu.dao.entity.Message;
import com.lianyu.dao.mapper.CharacterMapper;
import com.lianyu.dao.mapper.ConversationMapper;
import com.lianyu.dao.mapper.MessageMapper;
import com.lianyu.dao.mapper.MomentsPostMapper;
import com.lianyu.service.conversation.EngagementFrequencyScorer;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class MomentsScheduler {

    private final ConversationMapper conversationMapper;
    private final CharacterMapper characterMapper;
    private final MessageMapper messageMapper;
    private final MomentsPostMapper momentsPostMapper;
    private final MomentsService momentsService;
    private final EngagementFrequencyScorer engagementScorer;

    @Value("${lianyu.moments.enabled:true}")
    private boolean momentsEnabled;

    @Value("${lianyu.moments.scan-limit:40}")
    private int scanLimit;

    @Value("${lianyu.moments.max-posts-per-run:1}")
    private int maxPostsPerRun;

    @Value("${lianyu.moments.trigger-probability:0.12}")
    private double triggerProbability;

    @Scheduled(fixedDelayString = "${lianyu.moments.scan-interval-ms:300000}")
    public void tick() {
        if (!momentsEnabled) {
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
        Set<Long> userIds = candidates.stream()
                .map(Conversation::getUserId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, Character> characterMap = new HashMap<>();
        if (!characterIds.isEmpty()) {
            characterMapper.selectByIds(characterIds).forEach(c -> characterMap.put(c.getId(), c));
        }
        List<Long> convIds = candidates.stream().map(Conversation::getId).toList();
        Map<Long, Message> latestUserMessageMap = messageMapper.selectLatestUserByConversationIds(convIds).stream()
                .filter(m -> m.getConversationId() != null)
                .collect(Collectors.toMap(Message::getConversationId, m -> m, (a, b) -> a));

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime activitySince = now.minusDays(engagementScorer.momentsActivityWindowDays());
        Map<Long, Long> userMsgCountMap = loadUserMessageCounts(convIds, activitySince);

        Map<String, Long> todayPostCountMap = new HashMap<>();
        if (!userIds.isEmpty() && !characterIds.isEmpty()) {
            LocalDateTime startOfDay = LocalDate.now().atStartOfDay();
            List<MomentsDailyCountRow> dailyCounts = momentsPostMapper.selectTodayCountsByUsersAndCharacters(
                    startOfDay, new ArrayList<>(userIds), new ArrayList<>(characterIds));
            for (MomentsDailyCountRow row : dailyCounts) {
                if (row.getUserId() == null || row.getCharacterId() == null) {
                    continue;
                }
                todayPostCountMap.put(key(row.getUserId(), row.getCharacterId()),
                        row.getTotal() == null ? 0L : row.getTotal());
            }
        }

        double baseP = Math.max(0.0, Math.min(1.0, triggerProbability));
        List<WeightedCandidate> weighted = new ArrayList<>();
        for (Conversation conv : candidates) {
            Character character = characterMap.get(conv.getCharacterId());
            if (character == null) {
                continue;
            }
            Message lastUser = latestUserMessageMap.get(conv.getId());
            int userMsgs = userMsgCountMap.getOrDefault(conv.getId(), 0L).intValue();
            LocalDateTime lastUserAt = lastUser != null ? lastUser.getCreatedAt() : null;
            double multiplier = engagementScorer.momentsProbabilityMultiplier(userMsgs, lastUserAt, now);
            double effectiveP = baseP * multiplier;
            if (effectiveP <= 0.02) {
                continue;
            }
            weighted.add(new WeightedCandidate(conv, character, lastUser, effectiveP));
        }

        if (weighted.isEmpty()) {
            return;
        }

        weighted.sort(Comparator.comparingDouble(WeightedCandidate::effectiveP).reversed());

        int created = 0;
        int quota = Math.max(1, maxPostsPerRun);
        for (WeightedCandidate item : weighted) {
            if (created >= quota) {
                break;
            }
            if (ThreadLocalRandom.current().nextDouble() > item.effectiveP()) {
                continue;
            }
            try {
                Conversation conv = item.conv();
                Long todayCount = todayPostCountMap.getOrDefault(
                        key(conv.getUserId(), conv.getCharacterId()), 0L);
                if (momentsService.enqueueGenerateForConversation(conv, item.character())) {
                    created++;
                    // 入队即占本轮配额；实际落库由消费者侧 cooldown/日限再校验
                    todayPostCountMap.put(
                            key(conv.getUserId(), conv.getCharacterId()), todayCount + 1);
                }
            } catch (Exception e) {
                log.debug("Moments tick skipped: convId={}, reason={}", item.conv().getId(), e.getMessage());
            }
        }
        if (created > 0) {
            log.info("Moments scheduler run finished: created={}", created);
        }
    }

    private Map<Long, Long> loadUserMessageCounts(List<Long> convIds, LocalDateTime since) {
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

    private String key(Long userId, Long characterId) {
        return userId + ":" + characterId;
    }

    private record WeightedCandidate(
            Conversation conv,
            Character character,
            Message lastUser,
            double effectiveP) {
    }
}
