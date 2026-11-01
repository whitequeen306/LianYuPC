package com.lianyu.service.relationship;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lianyu.dao.entity.Message;
import com.lianyu.dao.entity.RelationshipEvent;
import com.lianyu.dao.entity.RelationshipMilestone;
import com.lianyu.dao.entity.RelationshipState;
import com.lianyu.dao.mapper.RelationshipEventMapper;
import com.lianyu.dao.mapper.RelationshipMilestoneMapper;
import com.lianyu.dao.mapper.RelationshipStateMapper;
import com.lianyu.service.dto.MessageResponse;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class RelationshipStateService {

    /** 每天第一次出现就 +3 亲密：陪伴的基本盘不该依赖用户说出某句台词。 */
    private static final int PRESENCE_INTIMACY = 3;
    private static final int PRESENCE_TRUST = 1;
    /** 出现会释放想念：期待在你回来时回落，形成「不见面积累 / 见面释放」的循环。 */
    private static final int PRESENCE_ANTICIPATION_RELIEF = 6;

    private static final int ANTICIPATION_PER_DAY = 3;
    /** 单次结算最多按 30 天算：老账号首次跑衰减时不该被一口气扣掉几个月。 */
    private static final int MAX_DECAY_DAYS = 30;
    private static final int SECURITY_BASELINE = 40;
    private static final int INTIMACY_FLOOR = 20;
    private static final long INTIMACY_GRACE_HOURS = 72;
    /** 单次衰减结算的批量上限，避免全表扫描拖垮调度。 */
    private static final int DECAY_BATCH_LIMIT = 500;

    private final RelationshipStateMapper relationshipStateMapper;
    private final RelationshipEventMapper relationshipEventMapper;
    private final RelationshipInnerSpaceAssembler relationshipInnerSpaceAssembler;
    private final RelationshipMilestoneMapper relationshipMilestoneMapper;

    public RelationshipSnapshot getSnapshot(Long userId, Long characterId) {
        return toSnapshot(loadOrCreateState(userId, characterId));
    }

    /**
     * Batch-load existing relationship snapshots for the given character ids.
     * Key format: {@code userId:characterId}. Missing pairs are omitted —
     * callers should fall back to {@link #getSnapshot} when create-on-miss is needed.
     */
    public Map<String, RelationshipSnapshot> mapExistingByCharacterIds(Collection<Long> characterIds) {
        if (characterIds == null || characterIds.isEmpty()) {
            return Map.of();
        }
        Set<Long> ids = characterIds.stream()
                .filter(id -> id != null && id > 0)
                .collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Map.of();
        }
        List<RelationshipState> states = relationshipStateMapper.selectList(
                new LambdaQueryWrapper<RelationshipState>()
                        .in(RelationshipState::getCharacterId, ids));
        Map<String, RelationshipSnapshot> map = new HashMap<>();
        for (RelationshipState state : states) {
            if (state.getUserId() != null && state.getCharacterId() != null) {
                map.put(state.getUserId() + ":" + state.getCharacterId(), toSnapshot(state));
            }
        }
        return map;
    }

    /**
     * 一次查询同时拿到快照、内心独白、陪伴事实与里程碑，供对外的状态接口使用：
     * 分开调用会让同一请求重复读 relationship_state。
     */
    public RelationshipView loadView(Long userId, Long characterId) {
        RelationshipState state = loadOrCreateState(userId, characterId);
        RelationshipSnapshot snapshot = toSnapshot(state);
        RelationshipInnerSpace innerSpace = relationshipInnerSpaceAssembler.assemble(
                snapshot, listRecentEventSummaries(userId, characterId, 5));
        return new RelationshipView(
                snapshot,
                innerSpace,
                toPresence(state, snapshot, LocalDateTime.now()),
                listMilestones(userId, characterId));
    }

    public RelationshipInnerSpace buildInnerSpace(Long userId, Long characterId) {
        return loadView(userId, characterId).innerSpace();
    }

    public record RelationshipView(RelationshipSnapshot snapshot,
                                   RelationshipInnerSpace innerSpace,
                                   RelationshipPresence presence,
                                   List<RelationshipMilestone> milestones) {
    }

    public void recordUserTurn(Long userId,
                               Long characterId,
                               Long conversationId,
                               Message userMessage,
                               List<Message> history) {
        if (userMessage == null) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        RelationshipState state = loadOrCreateState(userId, characterId);
        boolean firstContactEver = state.getLastInteractionAt() == null;
        String previousAssistantText = findPreviousAssistantText(history);
        List<RelationshipEventInput> events = new ArrayList<>(RelationshipHeuristics.fromUserTurn(
                userId,
                characterId,
                conversationId,
                userMessage.getId(),
                userMessage.getContent(),
                previousAssistantText));
        if (isNewPresenceDay(state, now)) {
            events.add(new RelationshipEventInput(userId, characterId, conversationId, userMessage.getId(),
                    RelationshipEventType.DAILY_PRESENCE, 1, null));
        }
        applyEvents(state, events, now);
        applyPresence(state, now);
        relationshipStateMapper.updateById(state);
        recordMilestones(state, events, now, firstContactEver);
    }

    public void recordAssistantTurn(Long userId,
                                    Long characterId,
                                    Long conversationId,
                                    List<MessageResponse> replies) {
        if (replies == null || replies.isEmpty()) {
            return;
        }
        List<RelationshipEventInput> events = new ArrayList<>();
        for (MessageResponse reply : replies) {
            if (reply == null || reply.getContent() == null) {
                continue;
            }
            String content = reply.getContent().trim();
            if (RelationshipHeuristics.isComfortingReply(content)) {
                events.add(new RelationshipEventInput(
                        userId,
                        characterId,
                        conversationId,
                        reply.getId(),
                        RelationshipEventType.ASSISTANT_VULNERABLE_REPLY,
                        1,
                        RelationshipHeuristics.excerpt(content)));
            }
        }
        if (events.isEmpty()) {
            return;
        }
        RelationshipState state = loadOrCreateState(userId, characterId);
        applyEvents(state, events, LocalDateTime.now());
        relationshipStateMapper.updateById(state);
    }

    /**
     * 时间衰减：让「不在一起的时间」也参与关系。
     * 每天最多结算一次（按 last_decay_at 计整天），避免 30 分钟轮询把小于 1 的增量舍入成 0。
     */
    public int decayAll() {
        LocalDateTime now = LocalDateTime.now();
        List<RelationshipState> states = relationshipStateMapper.selectList(
                new LambdaQueryWrapper<RelationshipState>()
                        .isNotNull(RelationshipState::getLastInteractionAt)
                        .orderByAsc(RelationshipState::getLastInteractionAt)
                        .last("LIMIT " + DECAY_BATCH_LIMIT));
        int updated = 0;
        for (RelationshipState state : states) {
            if (applyDecay(state, now)) {
                relationshipStateMapper.updateById(state);
                updated++;
            }
        }
        return updated;
    }

    static boolean applyDecay(RelationshipState state, LocalDateTime now) {
        LocalDateTime anchor = state.getLastDecayAt() != null
                ? state.getLastDecayAt()
                : (state.getLastInteractionAt() != null ? state.getLastInteractionAt() : state.getCreatedAt());
        if (anchor == null) {
            return false;
        }
        long days = ChronoUnit.DAYS.between(anchor, now);
        if (days < 1) {
            return false;
        }
        long awayHours = state.getLastInteractionAt() == null
                ? 0L
                : Math.max(0L, ChronoUnit.HOURS.between(state.getLastInteractionAt(), now));
        int elapsed = (int) Math.min(days, MAX_DECAY_DAYS);

        state.setAnticipationScore(clamp(
                defaultScore(state.getAnticipationScore(), 25) + elapsed * ANTICIPATION_PER_DAY));
        if (awayHours > INTIMACY_GRACE_HOURS) {
            // 三天宽限后才缓慢回落，且有地板：疏远是感觉，不是惩罚性清零
            state.setIntimacyScore(Math.max(INTIMACY_FLOOR,
                    defaultScore(state.getIntimacyScore(), 20) - elapsed));
        }
        int security = defaultScore(state.getSecurityScore(), SECURITY_BASELINE);
        if (security < SECURITY_BASELINE) {
            state.setSecurityScore(Math.min(SECURITY_BASELINE, security + elapsed));
        } else if (security > SECURITY_BASELINE) {
            state.setSecurityScore(Math.max(SECURITY_BASELINE, security - elapsed));
        }
        state.setLastDecayAt(now);
        RelationshipSnapshot snapshot = toSnapshot(state);
        state.setPhase(RelationshipPhaseResolver.derive(
                snapshot, state.getLastInjuryAt(), state.getLastRepairAt(), now).name());
        return true;
    }

    private void applyEvents(RelationshipState state, List<RelationshipEventInput> events, LocalDateTime now) {
        if (events == null || events.isEmpty()) {
            return;
        }
        RelationshipSnapshot snapshot = toSnapshot(state);
        for (RelationshipEventInput event : events) {
            snapshot = applyEvent(snapshot, event);
            persistEvent(event);
            switch (event.eventType()) {
                case USER_DISMISSIVE_RESPONSE, USER_BROKE_PROMISE, MISUNDERSTANDING_TRIGGERED ->
                        state.setLastInjuryAt(now);
                case REPAIR_ATTEMPT, REPAIR_SUCCESS -> state.setLastRepairAt(now);
                default -> {
                }
            }
        }
        state.setTrustScore(snapshot.trustScore());
        state.setIntimacyScore(snapshot.intimacyScore());
        state.setSecurityScore(snapshot.securityScore());
        state.setAnticipationScore(snapshot.anticipationScore());
        state.setPhase(RelationshipPhaseResolver.derive(
                snapshot, state.getLastInjuryAt(), state.getLastRepairAt(), now).name());
    }

    private void applyPresence(RelationshipState state, LocalDateTime now) {
        LocalDate today = now.toLocalDate();
        LocalDate previousAnchor = state.getStreakAnchorDate();
        int streak = state.getStreakDays() == null ? 0 : state.getStreakDays();
        if (previousAnchor == null) {
            streak = 1;
        } else if (previousAnchor.equals(today)) {
            streak = Math.max(1, streak);
        } else if (previousAnchor.equals(today.minusDays(1))) {
            streak += 1;
        } else {
            // 断签只重置连续天数，不倒扣分数
            streak = 1;
        }
        state.setStreakDays(streak);
        state.setStreakAnchorDate(today);
        state.setLastInteractionAt(now);
    }

    private boolean isNewPresenceDay(RelationshipState state, LocalDateTime now) {
        return state.getLastInteractionAt() == null
                || !state.getLastInteractionAt().toLocalDate().equals(now.toLocalDate());
    }

    /**
     * 只写「还没讲过的」里程碑。
     *
     * 先读一次已有关键字再插，而不是靠唯一键冲突兜底：否则用户在第 30 天之后
     * 每发一句话，都会有 6~9 条 INSERT 撞唯一键抛异常，白白打一屏错误日志并压 DB。
     */
    private void recordMilestones(RelationshipState state,
                                  List<RelationshipEventInput> events,
                                  LocalDateTime now,
                                  boolean firstContact) {
        Long userId = state.getUserId();
        Long characterId = state.getCharacterId();
        Set<String> recorded = loadMilestoneKeys(userId, characterId);

        if (firstContact) {
            recordMilestone(userId, characterId, RelationshipMilestones.FIRST_CONTACT, now, recorded);
        }
        for (RelationshipEventInput event : events) {
            switch (event.eventType()) {
                case USER_VULNERABLE_SHARE ->
                        recordMilestone(userId, characterId, RelationshipMilestones.FIRST_VULNERABLE, now, recorded);
                case REPAIR_ATTEMPT, REPAIR_SUCCESS ->
                        recordMilestone(userId, characterId, RelationshipMilestones.FIRST_REPAIR, now, recorded);
                case SPECIAL_NICKNAME_CREATED ->
                        recordMilestone(userId, characterId, RelationshipMilestones.FIRST_NICKNAME, now, recorded);
                case RITUAL_ESTABLISHED ->
                        recordMilestone(userId, characterId, RelationshipMilestones.FIRST_RITUAL, now, recorded);
                default -> {
                }
            }
        }
        int daysKnown = daysKnown(state, now);
        if (daysKnown >= 7) {
            recordMilestone(userId, characterId, RelationshipMilestones.DAY_7, now, recorded);
        }
        if (daysKnown >= 30) {
            recordMilestone(userId, characterId, RelationshipMilestones.DAY_30, now, recorded);
        }
        if (daysKnown >= 100) {
            recordMilestone(userId, characterId, RelationshipMilestones.DAY_100, now, recorded);
        }
        int streak = state.getStreakDays() == null ? 0 : state.getStreakDays();
        if (streak >= 3) {
            recordMilestone(userId, characterId, RelationshipMilestones.STREAK_3, now, recorded);
        }
        if (streak >= 7) {
            recordMilestone(userId, characterId, RelationshipMilestones.STREAK_7, now, recorded);
        }
        // 走 parsePhase：历史行可能是脏值，valueOf 会在每一轮对话里抛错
        switch (parsePhase(state.getPhase())) {
            case FAMILIAR ->
                    recordMilestone(userId, characterId, RelationshipMilestones.PHASE_FAMILIAR, now, recorded);
            case DEPENDENT ->
                    recordMilestone(userId, characterId, RelationshipMilestones.PHASE_DEPENDENT, now, recorded);
            case STABLE_INTIMATE ->
                    recordMilestone(userId, characterId, RelationshipMilestones.PHASE_STABLE_INTIMATE, now, recorded);
            default -> {
            }
        }
    }

    private Set<String> loadMilestoneKeys(Long userId, Long characterId) {
        return relationshipMilestoneMapper.selectList(new LambdaQueryWrapper<RelationshipMilestone>()
                        .eq(RelationshipMilestone::getUserId, userId)
                        .eq(RelationshipMilestone::getCharacterId, characterId))
                .stream()
                .map(RelationshipMilestone::getMilestoneKey)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
    }

    private void recordMilestone(Long userId, Long characterId, String key,
                                 LocalDateTime happenedAt, Set<String> recorded) {
        if (recorded.contains(key)) {
            return;
        }
        RelationshipMilestone milestone = new RelationshipMilestone();
        milestone.setUserId(userId);
        milestone.setCharacterId(characterId);
        milestone.setMilestoneKey(key);
        milestone.setHappenedAt(happenedAt);
        try {
            relationshipMilestoneMapper.insert(milestone);
            recorded.add(key);
        } catch (DuplicateKeyException ignored) {
            // 并发兜底：另一个请求刚插过
            recorded.add(key);
        }
    }

    private List<RelationshipMilestone> listMilestones(Long userId, Long characterId) {
        return relationshipMilestoneMapper.selectList(new LambdaQueryWrapper<RelationshipMilestone>()
                .eq(RelationshipMilestone::getUserId, userId)
                .eq(RelationshipMilestone::getCharacterId, characterId)
                .orderByAsc(RelationshipMilestone::getHappenedAt)
                .last("LIMIT 50"));
    }

    private RelationshipPresence toPresence(RelationshipState state, RelationshipSnapshot snapshot, LocalDateTime now) {
        long awayHours = state.getLastInteractionAt() == null
                ? 0L
                : Math.max(0L, ChronoUnit.HOURS.between(state.getLastInteractionAt(), now));
        RelationshipPhase phase = snapshot.phase();
        return new RelationshipPresence(
                daysKnown(state, now),
                state.getStreakDays() == null ? 0 : state.getStreakDays(),
                awayHours,
                state.getLastInteractionAt(),
                RelationshipPhaseResolver.nextPhase(phase),
                RelationshipPhaseResolver.progressToNext(snapshot, phase));
    }

    private static int daysKnown(RelationshipState state, LocalDateTime now) {
        if (state.getCreatedAt() == null) {
            return 1;
        }
        return (int) Math.max(1,
                ChronoUnit.DAYS.between(state.getCreatedAt().toLocalDate(), now.toLocalDate()) + 1);
    }

    private void persistEvent(RelationshipEventInput input) {
        RelationshipEvent event = new RelationshipEvent();
        event.setUserId(input.userId());
        event.setCharacterId(input.characterId());
        event.setConversationId(input.conversationId());
        event.setMessageId(input.messageId());
        event.setEventType(input.eventType().name());
        event.setEventWeight(input.weight());
        event.setSummary(input.summary());
        event.setMetadataJson(new HashMap<>());
        relationshipEventMapper.insert(event);
    }

    private List<String> listRecentEventSummaries(Long userId, Long characterId, int limit) {
        return relationshipEventMapper.selectList(new LambdaQueryWrapper<RelationshipEvent>()
                        .eq(RelationshipEvent::getUserId, userId)
                        .eq(RelationshipEvent::getCharacterId, characterId)
                        .isNotNull(RelationshipEvent::getSummary)
                        .orderByDesc(RelationshipEvent::getCreatedAt)
                        .last("LIMIT " + Math.max(1, limit)))
                .stream()
                .map(RelationshipEvent::getSummary)
                .toList();
    }

    private RelationshipState loadOrCreateState(Long userId, Long characterId) {
        RelationshipState existing = relationshipStateMapper.selectOne(new LambdaQueryWrapper<RelationshipState>()
                .eq(RelationshipState::getUserId, userId)
                .eq(RelationshipState::getCharacterId, characterId)
                .last("LIMIT 1"));
        if (existing != null) {
            return existing;
        }
        RelationshipState created = new RelationshipState();
        created.setUserId(userId);
        created.setCharacterId(characterId);
        created.setTrustScore(40);
        created.setIntimacyScore(20);
        created.setSecurityScore(40);
        created.setAnticipationScore(25);
        created.setPhase(RelationshipPhase.TESTING.name());
        try {
            relationshipStateMapper.insert(created);
            return created;
        } catch (DuplicateKeyException e) {
            RelationshipState existingAfterRace = waitForExistingState(userId, characterId);
            if (existingAfterRace != null) {
                return existingAfterRace;
            }
            throw new IllegalStateException("Failed to create or find relationship state", e);
        }
    }

    private RelationshipState waitForExistingState(Long userId, Long characterId) {
        for (int attempt = 0; attempt < 5; attempt++) {
            RelationshipState state = relationshipStateMapper.selectOne(new LambdaQueryWrapper<RelationshipState>()
                    .eq(RelationshipState::getUserId, userId)
                    .eq(RelationshipState::getCharacterId, characterId)
                    .last("LIMIT 1"));
            if (state != null) {
                return state;
            }
            try {
                Thread.sleep(20L * (attempt + 1));
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return relationshipStateMapper.selectOne(new LambdaQueryWrapper<RelationshipState>()
                .eq(RelationshipState::getUserId, userId)
                .eq(RelationshipState::getCharacterId, characterId)
                .last("LIMIT 1"));
    }

    private static RelationshipSnapshot toSnapshot(RelationshipState state) {
        return RelationshipSnapshot.builder()
                .trustScore(defaultScore(state.getTrustScore(), 40))
                .intimacyScore(defaultScore(state.getIntimacyScore(), 20))
                .securityScore(defaultScore(state.getSecurityScore(), 40))
                .anticipationScore(defaultScore(state.getAnticipationScore(), 25))
                .phase(parsePhase(state.getPhase()))
                .build();
    }

    private String findPreviousAssistantText(List<Message> history) {
        if (history == null || history.isEmpty()) {
            return null;
        }
        for (int i = history.size() - 1; i >= 0; i--) {
            Message message = history.get(i);
            if (message != null && "ASSISTANT".equalsIgnoreCase(message.getRole())) {
                return message.getContent();
            }
        }
        return null;
    }

    private static int defaultScore(Integer value, int fallback) {
        return value == null ? fallback : value;
    }

    private static RelationshipPhase parsePhase(String raw) {
        if (raw == null || raw.isBlank()) {
            return RelationshipPhase.TESTING;
        }
        try {
            return RelationshipPhase.valueOf(raw);
        } catch (IllegalArgumentException ignored) {
            return RelationshipPhase.TESTING;
        }
    }

    public static RelationshipSnapshot applyEvent(RelationshipSnapshot before, RelationshipEventInput input) {
        int trust = before.trustScore();
        int intimacy = before.intimacyScore();
        int security = before.securityScore();
        int anticipation = before.anticipationScore();
        switch (input.eventType()) {
            case DAILY_PRESENCE -> {
                intimacy += PRESENCE_INTIMACY;
                trust += PRESENCE_TRUST;
                anticipation -= PRESENCE_ANTICIPATION_RELIEF;
            }
            case USER_VULNERABLE_SHARE -> {
                trust += 5 * input.weight();
                intimacy += 3 * input.weight();
            }
            case USER_WARM_RESPONSE -> {
                intimacy += 3 * input.weight();
                anticipation += 2 * input.weight();
            }
            case ASSISTANT_VULNERABLE_REPLY -> {
                // 以前没有 case：角色再怎么安抚，关系都纹丝不动
                security += 3;
                trust += 2;
            }
            case USER_DISMISSIVE_RESPONSE, USER_BROKE_PROMISE, MISUNDERSTANDING_TRIGGERED -> {
                security -= 8 * input.weight();
                anticipation -= 4 * input.weight();
            }
            case REPAIR_ATTEMPT -> {
                trust += 4 * input.weight();
                security += 3 * input.weight();
            }
            case REPAIR_SUCCESS -> {
                trust += 6 * input.weight();
                security += 10 * input.weight();
                intimacy += 3 * input.weight();
            }
            case SPECIAL_NICKNAME_CREATED, RITUAL_ESTABLISHED -> {
                intimacy += 8 * input.weight();
                anticipation += 4 * input.weight();
            }
            default -> {
            }
        }
        RelationshipSnapshot next = before.toBuilder()
                .trustScore(clamp(trust))
                .intimacyScore(clamp(intimacy))
                .securityScore(clamp(security))
                .anticipationScore(clamp(anticipation))
                .build();
        return next.toBuilder()
                .phase(derivePhase(next, input.eventType() == RelationshipEventType.MISUNDERSTANDING_TRIGGERED,
                        input.eventType() == RelationshipEventType.REPAIR_SUCCESS))
                .build();
    }

    public static RelationshipPhase derivePhase(RelationshipSnapshot snapshot, boolean hasRecentInjury, boolean hasRecentRepair) {
        LocalDateTime now = LocalDateTime.now();
        return RelationshipPhaseResolver.derive(
                snapshot,
                hasRecentInjury ? now : null,
                hasRecentRepair ? now : null,
                now);
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(100, value));
    }
}
