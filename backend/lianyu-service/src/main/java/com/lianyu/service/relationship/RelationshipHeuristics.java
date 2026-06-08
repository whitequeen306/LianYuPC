package com.lianyu.service.relationship;

import java.util.ArrayList;
import java.util.List;

public final class RelationshipHeuristics {

    private RelationshipHeuristics() {
    }

    public static List<RelationshipEventInput> fromUserTurn(
            Long userId,
            Long characterId,
            Long conversationId,
            Long messageId,
            String userText,
            String previousAssistantText,
            boolean brokePromise) {
        List<RelationshipEventInput> events = new ArrayList<>();
        String safe = userText == null ? "" : userText.trim();
        if (safe.length() <= 2 && previousAssistantText != null && previousAssistantText.contains("难过")) {
            events.add(new RelationshipEventInput(userId, characterId, conversationId, messageId,
                    RelationshipEventType.USER_DISMISSIVE_RESPONSE, 1, "短促回应切断了情绪话题"));
        }
        if (safe.contains("其实我有点难受") || safe.contains("我今天很崩溃") || safe.contains("我有点害怕")) {
            events.add(new RelationshipEventInput(userId, characterId, conversationId, messageId,
                    RelationshipEventType.USER_VULNERABLE_SHARE, 2, "用户主动暴露脆弱感受"));
        }
        if (safe.contains("对不起") || safe.contains("我解释一下") || safe.contains("我不是故意的")) {
            events.add(new RelationshipEventInput(userId, characterId, conversationId, messageId,
                    RelationshipEventType.REPAIR_ATTEMPT, 1, "用户尝试修复关系"));
        }
        if (brokePromise) {
            events.add(new RelationshipEventInput(userId, characterId, conversationId, messageId,
                    RelationshipEventType.USER_BROKE_PROMISE, 2, "用户打破已建立的小约定"));
        }
        return events;
    }
}
