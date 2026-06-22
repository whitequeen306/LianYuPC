package com.lianyu.service.relationship;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lianyu.dao.entity.Message;
import com.lianyu.dao.entity.RelationshipEvent;
import com.lianyu.dao.entity.RelationshipState;
import com.lianyu.dao.mapper.RelationshipEventMapper;
import com.lianyu.dao.mapper.RelationshipStateMapper;
import com.lianyu.service.dto.MessageResponse;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class RelationshipStateService {

    private final RelationshipStateMapper relationshipStateMapper;
    private final RelationshipEventMapper relationshipEventMapper;
    private final RelationshipContextAssembler relationshipContextAssembler;
    private final RelationshipInnerSpaceAssembler relationshipInnerSpaceAssembler;

    public RelationshipSnapshot getSnapshot(Long userId, Long characterId) {
        return toSnapshot(loadOrCreateState(userId, characterId));
    }

    public String buildPromptContext(Long userId, Long characterId) {
        RelationshipSnapshot snapshot = getSnapshot(userId, characterId);
        return relationshipContextAssembler.assemble(snapshot, listRecentEventSummaries(userId, characterId, 5));
    }

    public RelationshipInnerSpace buildInnerSpace(Long userId, Long characterId) {
        RelationshipSnapshot snapshot = getSnapshot(userId, characterId);
        return relationshipInnerSpaceAssembler.assemble(snapshot, listRecentEventSummaries(userId, characterId, 5));
    }

    public void recordUserTurn(Long userId,
                               Long characterId,
                               Long conversationId,
                               Message userMessage,
                               List<Message> history) {
        if (userMessage == null) {
            return;
        }
        String previousAssistantText = findPreviousAssistantText(history);
        List<RelationshipEventInput> events = RelationshipHeuristics.fromUserTurn(
                userId,
                characterId,
                conversationId,
                userMessage.getId(),
                userMessage.getContent(),
                previousAssistantText,
                false);
        applyEvents(userId, characterId, events);
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
            if (content.contains("我会一直在") || content.contains("别怕") || content.contains("我还在")) {
                events.add(new RelationshipEventInput(
                        userId,
                        characterId,
                        conversationId,
                        reply.getId(),
                        RelationshipEventType.ASSISTANT_VULNERABLE_REPLY,
                        1,
                        "角色给出了安抚和陪伴式回应"));
            }
        }
        applyEvents(userId, characterId, events);
    }

    private void applyEvents(Long userId, Long characterId, List<RelationshipEventInput> events) {
        if (events == null || events.isEmpty()) {
            return;
        }
        RelationshipState state = loadOrCreateState(userId, characterId);
        RelationshipSnapshot snapshot = toSnapshot(state);
        boolean hasRecentInjury = false;
        boolean hasRecentRepair = false;
        for (RelationshipEventInput event : events) {
            snapshot = applyEvent(snapshot, event);
            persistEvent(event);
            if (event.eventType() == RelationshipEventType.USER_DISMISSIVE_RESPONSE
                    || event.eventType() == RelationshipEventType.USER_BROKE_PROMISE
                    || event.eventType() == RelationshipEventType.MISUNDERSTANDING_TRIGGERED) {
                hasRecentInjury = true;
            }
            if (event.eventType() == RelationshipEventType.REPAIR_SUCCESS
                    || event.eventType() == RelationshipEventType.REPAIR_ATTEMPT) {
                hasRecentRepair = true;
            }
        }
        snapshot = snapshot.toBuilder()
                .phase(derivePhase(snapshot, hasRecentInjury, hasRecentRepair))
                .build();
        updateState(state, snapshot, hasRecentInjury, hasRecentRepair);
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

    private RelationshipSnapshot toSnapshot(RelationshipState state) {
        return RelationshipSnapshot.builder()
                .trustScore(defaultScore(state.getTrustScore(), 40))
                .intimacyScore(defaultScore(state.getIntimacyScore(), 20))
                .securityScore(defaultScore(state.getSecurityScore(), 40))
                .anticipationScore(defaultScore(state.getAnticipationScore(), 25))
                .phase(parsePhase(state.getPhase()))
                .build();
    }

    private void updateState(RelationshipState state,
                             RelationshipSnapshot snapshot,
                             boolean hasRecentInjury,
                             boolean hasRecentRepair) {
        state.setTrustScore(snapshot.trustScore());
        state.setIntimacyScore(snapshot.intimacyScore());
        state.setSecurityScore(snapshot.securityScore());
        state.setAnticipationScore(snapshot.anticipationScore());
        state.setPhase(snapshot.phase().name());
        if (hasRecentInjury) {
            state.setLastInjuryAt(LocalDateTime.now());
        }
        if (hasRecentRepair) {
            state.setLastRepairAt(LocalDateTime.now());
        }
        relationshipStateMapper.updateById(state);
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

    private int defaultScore(Integer value, int fallback) {
        return value == null ? fallback : value;
    }

    private RelationshipPhase parsePhase(String raw) {
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
            case USER_VULNERABLE_SHARE -> {
                trust += 6 * input.weight();
                intimacy += 4 * input.weight();
            }
            case USER_DISMISSIVE_RESPONSE, USER_BROKE_PROMISE, MISUNDERSTANDING_TRIGGERED -> {
                security -= 8 * input.weight();
                anticipation -= 5 * input.weight();
            }
            case REPAIR_ATTEMPT -> trust += 4 * input.weight();
            case REPAIR_SUCCESS -> {
                trust += 6 * input.weight();
                security += 8 * input.weight();
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
        if (hasRecentInjury && snapshot.securityScore() <= 25) {
            return RelationshipPhase.INJURED;
        }
        if (hasRecentRepair) {
            return RelationshipPhase.REPAIRING;
        }
        if (snapshot.intimacyScore() >= 70 && snapshot.trustScore() >= 65) {
            return RelationshipPhase.STABLE_INTIMATE;
        }
        if (snapshot.intimacyScore() >= 55) {
            return RelationshipPhase.DEPENDENT;
        }
        if (snapshot.trustScore() >= 45) {
            return RelationshipPhase.FAMILIAR;
        }
        return RelationshipPhase.TESTING;
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(100, value));
    }
}
