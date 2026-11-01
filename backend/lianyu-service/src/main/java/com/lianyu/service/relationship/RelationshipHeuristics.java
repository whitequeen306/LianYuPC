package com.lianyu.service.relationship;

import java.util.ArrayList;
import java.util.List;

/**
 * 用户回合 → 关系信号。
 *
 * 旧实现用「整句精确匹配」（必须一字不差打出「其实我有点难受」），真实用户几乎永远不命中，
 * 分数因此停在初始值。现在用词表 + 命中数定权重：识别的是情绪方向，不是台词。
 *
 * summary 存的是**用户原话摘录**，不是分类标签 —— prompt 由 Python 组装，
 * Java 只提供事实，不再往提示词里写「用户主动暴露脆弱感受」这种文案。
 */
public final class RelationshipHeuristics {

    private static final int EXCERPT_LIMIT = 48;

    private static final String[] VULNERABLE = {
            "难受", "崩溃", "害怕", "压力", "焦虑", "失眠", "委屈", "孤独", "好累", "很累",
            "哭了", "想哭", "被骂", "分手", "失业", "撑不住", "心情不好", "难过", "沮丧", "失落", "绝望", "痛苦"
    };
    private static final String[] WARM = {
            "想你", "想念", "晚安", "早安", "抱抱", "喜欢你", "爱你", "么么", "亲一下",
            "撒娇", "贴贴", "陪你", "一直在", "最好了", "真温柔"
    };
    private static final String[] REPAIR = {
            "对不起", "抱歉", "我错", "解释一下", "不是故意", "别生气", "原谅我", "和好", "刚刚是我不"
    };
    /** 上一条是情绪话题时，极短回复才算「切断」；否则「嗯」只是正常应答。 */
    private static final String[] EMOTION_TOPIC = {
            "难过", "害怕", "委屈", "压力", "不开心", "生气", "失落", "想哭", "孤独", "焦虑"
    };
    private static final String[] NICKNAME = {"只给你", "专属", "以后", "我想让你", "单独"};
    private static final String[] RITUAL = {"晚安", "早安", "约定", "一起", "提醒我", "说一句话"};
    private static final String[] COMFORT = {"我会一直在", "别怕", "我还在", "我陪着你", "有我在"};

    private RelationshipHeuristics() {
    }

    public static List<RelationshipEventInput> fromUserTurn(
            Long userId,
            Long characterId,
            Long conversationId,
            Long messageId,
            String userText,
            String previousAssistantText) {
        List<RelationshipEventInput> events = new ArrayList<>();
        String safe = userText == null ? "" : userText.trim();
        if (safe.isEmpty()) {
            return events;
        }

        int vulnerableHits = countHits(safe, VULNERABLE);
        if (vulnerableHits > 0 && safe.length() >= 4) {
            events.add(input(userId, characterId, conversationId, messageId,
                    RelationshipEventType.USER_VULNERABLE_SHARE, weight(vulnerableHits), safe));
        }
        int warmHits = countHits(safe, WARM);
        if (warmHits > 0) {
            events.add(input(userId, characterId, conversationId, messageId,
                    RelationshipEventType.USER_WARM_RESPONSE, weight(warmHits), safe));
        }
        if (countHits(safe, REPAIR) > 0) {
            events.add(input(userId, characterId, conversationId, messageId,
                    RelationshipEventType.REPAIR_ATTEMPT, 1, safe));
        }
        // 专属称呼 / 小约定：这两个事件以前只有计分、没有生产者，主成长路径是断的。
        // 记忆层有独立的 RITUAL 抽取，这里只关心「关系因此近了一步」。
        if (safe.contains("叫我") && countHits(safe, NICKNAME) > 0) {
            events.add(input(userId, characterId, conversationId, messageId,
                    RelationshipEventType.SPECIAL_NICKNAME_CREATED, 1, safe));
        }
        if (safe.contains("每天") && countHits(safe, RITUAL) > 0) {
            events.add(input(userId, characterId, conversationId, messageId,
                    RelationshipEventType.RITUAL_ESTABLISHED, 1, safe));
        }
        if (safe.length() <= 2 && countHits(previousAssistantText, EMOTION_TOPIC) > 0) {
            events.add(input(userId, characterId, conversationId, messageId,
                    RelationshipEventType.USER_DISMISSIVE_RESPONSE, 1, safe));
        }
        return events;
    }

    /** 角色给出安抚回应：以前只落库不计分，等于白写一条事件。 */
    public static boolean isComfortingReply(String assistantText) {
        return countHits(assistantText, COMFORT) > 0;
    }

    /** 摘录原话供 prompt / 时间线引用；不带任何 Java 侧写的文案。 */
    public static String excerpt(String text) {
        if (text == null) {
            return null;
        }
        String collapsed = text.trim().replaceAll("\\s+", " ");
        if (collapsed.isEmpty()) {
            return null;
        }
        return collapsed.length() <= EXCERPT_LIMIT
                ? collapsed
                : collapsed.substring(0, EXCERPT_LIMIT);
    }

    private static RelationshipEventInput input(Long userId, Long characterId, Long conversationId,
                                                Long messageId, RelationshipEventType type, int weight,
                                                String rawText) {
        return new RelationshipEventInput(userId, characterId, conversationId, messageId,
                type, weight, excerpt(rawText));
    }

    private static int weight(int hits) {
        return Math.max(1, Math.min(3, hits));
    }

    private static int countHits(String text, String[] keywords) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int hits = 0;
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                hits++;
            }
        }
        return hits;
    }
}
