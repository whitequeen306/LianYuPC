package com.lianyu.service.character;

import com.lianyu.dao.entity.CharacterState;
import com.lianyu.dao.mapper.CharacterStateMapper;
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;



/**
 * 角色情绪与状态服务。
 * <ul>
 *   <li>情绪随时间自然衰减/变化</li>
 *   <li>对话后根据内容关键词调整情绪</li>
 *   <li>首页展示当前情绪 + 状态签名</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CharacterStateService {

    private static final List<String> EMOTIONS = List.of(
            "开心", "难过", "想念", "吃醋", "生气", "撒娇", "疲惫", "兴奋", "平静", "担心"
    );

    private static final Map<String, List<String>> EMOTION_TRANSITIONS = Map.of(
            "平静", List.of("开心", "想念", "疲惫", "平静"),
            "开心", List.of("平静", "兴奋", "撒娇", "想念"),
            "想念", List.of("难过", "撒娇", "平静", "担心"),
            "难过", List.of("平静", "想念", "担心", "生气"),
            "吃醋", List.of("生气", "难过", "撒娇", "平静"),
            "生气", List.of("平静", "难过", "吃醋", "担心"),
            "撒娇", List.of("开心", "想念", "平静", "吃醋"),
            "疲惫", List.of("平静", "想念", "难过", "担心"),
            "兴奋", List.of("开心", "撒娇", "平静", "想念"),
            "担心", List.of("想念", "难过", "平静", "撒娇")
    );

    private static final Map<String, String> EMOTION_DEFAULT_STATUS = Map.of(
            "开心", "今天心情很好呢~",
            "难过", "有点不开心…",
            "想念", "好想见你…",
            "吃醋", "哼，你在跟谁聊天？",
            "生气", "不想理你了。",
            "撒娇", "过来陪陪我嘛~",
            "疲惫", "今天好累啊…",
            "兴奋", "有好多话想跟你说！",
            "平静", "一切安好。",
            "担心", "你还好吗…有点担心你。"
    );

    private final CharacterStateMapper stateMapper;

    @Value("${lianyu.emotion.decay-interval-minutes:60}")
    private int decayIntervalMinutes;

    /** 用户向角色道歉/哄 → 角色情绪缓和 */
    private static final Map<String, String> KEYWORD_EMOTION = new LinkedHashMap<>();
    static {
        // 顺序重要：更具体的模式放前面
        KEYWORD_EMOTION.put("对不起|抱歉|我错了|别生气|哄哄|原谅我|不要生气|是我不对", "撒娇");
        KEYWORD_EMOTION.put("讨厌你|恨你|生你气|生你的气|不理你了|别跟我说话|滚开|走开啊", "生气");
        KEYWORD_EMOTION.put("好烦|好累|累了|困死了|压力大|焦虑|郁闷|不开心|很难受|好难过|想哭|烦死了|心累|崩溃|委屈", "担心");
        KEYWORD_EMOTION.put("开心|哈哈|嘻嘻|太好了|真棒|喜欢|爱你|love|大好き|すごい", "开心");
        KEYWORD_EMOTION.put("难过|伤心|哭|流泪|好痛|心痛|寂寞|孤独|寂しい", "难过");
        KEYWORD_EMOTION.put("想你|想你了|想见你|什么时候来|等你|回来|待って|miss you", "想念");
        KEYWORD_EMOTION.put("跟谁|谁聊|别的|其他|另外|不理|花心|浮気|jealous", "吃醋");
        KEYWORD_EMOTION.put("陪我|抱抱|亲亲|撒娇|要你|想要|甘え|ねぇ|好不好嘛", "撒娇");
        KEYWORD_EMOTION.put("好累|累了|困|忙|加班|工作|疲|疲れた", "疲惫");
        KEYWORD_EMOTION.put("哇|天哪|真的吗|太棒了|好厉害|惊喜|激动|amazing", "兴奋");
        KEYWORD_EMOTION.put("担心|没事吧|还好吗|注意|小心|保重|大丈夫|気をつけて", "担心");
    }

    /**
     * 并发首聊同一角色时 select-then-insert 会在 uk_char_user 上死锁
     * （双方持 S 间隙锁、互等 insert intention），且 afterUserMessage 自调用
     * 使 NOT_SUPPORTED 失效、整个插入落入外层事务放大锁持有时间。
     * 改用单语句 upsert 后无 S→X 升级环，并发只在唯一键上短暂串行。
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public CharacterState getOrCreate(Long characterId, Long userId) {
        CharacterState state = findState(characterId, userId);
        if (state != null) {
            return state;
        }

        CharacterState toInsert = new CharacterState();
        toInsert.setCharacterId(characterId);
        toInsert.setUserId(userId);
        toInsert.setCurrentEmotion("平静");
        toInsert.setEmotionIntensity(50);
        toInsert.setStatusText("一切安好。");
        toInsert.setEmotionUpdatedAt(LocalDateTime.now());
        stateMapper.upsertDefault(toInsert);
        CharacterState existing = findState(characterId, userId);
        return existing != null ? existing : toInsert;
    }

    private CharacterState findState(Long characterId, Long userId) {
        return stateMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<CharacterState>()
                        .eq(CharacterState::getCharacterId, characterId)
                        .eq(CharacterState::getUserId, userId)
                        .last("LIMIT 1"));
    }

    /** 批量加载用户角色的情绪状态，缺失项按需创建。 */
    public Map<Long, CharacterState> mapForCharacters(Long userId, List<Long> characterIds) {
        if (characterIds == null || characterIds.isEmpty()) {
            return Map.of();
        }
        List<CharacterState> existing = stateMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<CharacterState>()
                        .eq(CharacterState::getUserId, userId)
                        .in(CharacterState::getCharacterId, characterIds));
        Map<Long, CharacterState> result = new LinkedHashMap<>();
        for (CharacterState state : existing) {
            result.put(state.getCharacterId(), state);
        }
        for (Long characterId : characterIds) {
            if (!result.containsKey(characterId)) {
                result.put(characterId, getOrCreate(characterId, userId));
            }
        }
        return result;
    }

    /**
     * 对话后根据用户消息内容调整角色情绪。
     */
    @Transactional
    public void afterUserMessage(Long characterId, Long userId, String userContent) {
        if (userContent == null || userContent.isBlank()) {
            return;
        }
        CharacterState state = getOrCreate(characterId, userId);

        String matched = matchEmotion(userContent);
        if (matched == null) {
            return;
        }

        String previous = state.getCurrentEmotion();
        int newIntensity;
        if (matched.equals(previous)) {
            newIntensity = Math.min(100, state.getEmotionIntensity() + ThreadLocalRandom.current().nextInt(10, 26));
        } else {
            newIntensity = 55 + ThreadLocalRandom.current().nextInt(15, 36);
        }

        state.setPreviousEmotion(previous);
        state.setCurrentEmotion(matched);
        state.setEmotionIntensity(newIntensity);
        state.setStatusText(EMOTION_DEFAULT_STATUS.getOrDefault(matched, "…"));
        state.setEmotionUpdatedAt(LocalDateTime.now());
        stateMapper.updateById(state);

        if (!matched.equals(previous)) {
            log.info("Character emotion changed: charId={}, {} -> {}", characterId, previous, matched);
        }
    }

    /**
     * 定时衰减：情绪向"平静"回落，强度逐渐降低。
     * 返回实际更新的数量。
     */
    @Transactional
    public int decayAllEmotions() {
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(decayIntervalMinutes);
        List<CharacterState> candidates = stateMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<CharacterState>()
                        .isNotNull(CharacterState::getCurrentEmotion)
                        .ne(CharacterState::getCurrentEmotion, "平静")
                        .and(w -> w.isNull(CharacterState::getEmotionUpdatedAt)
                                .or()
                                .le(CharacterState::getEmotionUpdatedAt, threshold)));

        int updated = 0;
        for (CharacterState state : candidates) {
            if (state.getEmotionUpdatedAt() == null) {
                state.setEmotionUpdatedAt(LocalDateTime.now());
                stateMapper.updateById(state);
                continue;
            }
            int currentIntensity = state.getEmotionIntensity() != null ? state.getEmotionIntensity() : 50;
            int newIntensity = Math.max(0, currentIntensity - ThreadLocalRandom.current().nextInt(5, 16));
            if (newIntensity <= 15) {
                state.setPreviousEmotion(state.getCurrentEmotion());
                state.setCurrentEmotion("平静");
                state.setEmotionIntensity(50);
                state.setStatusText("一切安好。");
            } else {
                state.setEmotionIntensity(newIntensity);
                if (ThreadLocalRandom.current().nextDouble() < 0.15) {
                    List<String> transitions = EMOTION_TRANSITIONS.getOrDefault(
                            state.getCurrentEmotion(), List.of("平静"));
                    String newEmotion = transitions.get(ThreadLocalRandom.current().nextInt(transitions.size()));
                    if (!newEmotion.equals(state.getCurrentEmotion())) {
                        state.setPreviousEmotion(state.getCurrentEmotion());
                        state.setCurrentEmotion(newEmotion);
                        state.setEmotionIntensity(40 + ThreadLocalRandom.current().nextInt(10, 31));
                        state.setStatusText(EMOTION_DEFAULT_STATUS.getOrDefault(newEmotion, "…"));
                    }
                }
            }
            state.setEmotionUpdatedAt(LocalDateTime.now());
            stateMapper.updateById(state);
            updated++;
        }
        if (updated > 0) {
            log.info("Emotion decay updated {} character states", updated);
        }
        return updated;
    }

    public String getStatusText(Long characterId, Long userId) {
        CharacterState state = getOrCreate(characterId, userId);
        return state.getStatusText() != null ? state.getStatusText() : "一切安好。";
    }


    private String matchEmotion(String content) {
        for (var entry : KEYWORD_EMOTION.entrySet()) {
            String[] keywords = entry.getKey().split("\\|");
            for (String kw : keywords) {
                if (content.contains(kw)) {
                    return entry.getValue();
                }
            }
        }
        return null;
    }

    public static boolean isValidEmotion(String emotion) {
        return emotion != null && EMOTIONS.contains(emotion);
    }
}
