package com.lianyu.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lianyu.dao.dto.MomentsDailyCountRow;
import com.lianyu.dao.entity.Character;
import com.lianyu.dao.entity.Conversation;
import com.lianyu.dao.entity.Message;
import com.lianyu.dao.mapper.CharacterMapper;
import com.lianyu.dao.mapper.ConversationMapper;
import com.lianyu.dao.mapper.MessageMapper;
import com.lianyu.dao.mapper.MomentsPostMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class MomentsScheduler {

    private final ConversationMapper conversationMapper;
    private final CharacterMapper characterMapper;
    private final MessageMapper messageMapper;
    private final MomentsPostMapper momentsPostMapper;
    private final MomentsService momentsService;

    @Value("${lianyu.moments.enabled:true}")
    private boolean momentsEnabled;

    @Value("${lianyu.moments.scan-limit:40}")
    private int scanLimit;

    @Value("${lianyu.moments.max-posts-per-run:2}")
    private int maxPostsPerRun;

    @Value("${lianyu.moments.trigger-probability:0.35}")
    private double triggerProbability;

    @Scheduled(fixedDelayString = "${lianyu.moments.scan-interval-ms:120000}")
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

        Map<String, Long> todayPostCountMap = new HashMap<>();
        if (!userIds.isEmpty() && !characterIds.isEmpty()) {
            LocalDateTime startOfDay = LocalDate.now().atStartOfDay();
            List<MomentsDailyCountRow> dailyCounts = momentsPostMapper.selectTodayCountsByUsersAndCharacters(
                    startOfDay, new ArrayList<>(userIds), new ArrayList<>(characterIds));
            for (MomentsDailyCountRow row : dailyCounts) {
                if (row.getUserId() == null || row.getCharacterId() == null) {
                    continue;
                }
                todayPostCountMap.put(key(row.getUserId(), row.getCharacterId()), row.getTotal() == null ? 0L : row.getTotal());
            }
        }

        List<Conversation> shuffled = new ArrayList<>(candidates);
        Collections.shuffle(shuffled);

        int created = 0;
        double p = Math.max(0.0, Math.min(1.0, triggerProbability));
        for (Conversation conv : shuffled) {
            if (created >= Math.max(1, maxPostsPerRun)) {
                break;
            }
            if (ThreadLocalRandom.current().nextDouble() > p) {
                continue;
            }
            Character character = characterMap.get(conv.getCharacterId());
            if (character == null) {
                continue;
            }
            try {
                Message lastUser = latestUserMessageMap.get(conv.getId());
                Long todayCount = todayPostCountMap.getOrDefault(key(conv.getUserId(), conv.getCharacterId()), 0L);
                if (momentsService.tryGenerateForConversation(conv, character, lastUser, todayCount)) {
                    created++;
                }
            } catch (Exception e) {
                log.debug("Moments tick skipped: convId={}, reason={}", conv.getId(), e.getMessage());
            }
        }
        if (created > 0) {
            log.info("Moments scheduler run finished: created={}", created);
        }
    }

    private String key(Long userId, Long characterId) {
        return userId + ":" + characterId;
    }
}
