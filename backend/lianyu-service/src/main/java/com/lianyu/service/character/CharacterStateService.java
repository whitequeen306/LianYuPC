package com.lianyu.service.character;

import com.lianyu.common.i18n.OutputLanguage;
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

    public String buildEmotionBlock(Long characterId, Long userId) {
        return buildEmotionBlock(characterId, userId, "zh");
    }

    /**
     * 注入 Prompt 的情绪片段（放在回复规则之后，优先级高）。
     */
    public String buildEmotionBlock(Long characterId, Long userId, String outputLanguage) {
        CharacterState state = getOrCreate(characterId, userId);
        String emotion = state.getCurrentEmotion() != null ? state.getCurrentEmotion() : "平静";
        int intensity = state.getEmotionIntensity() != null ? state.getEmotionIntensity() : 50;
        String status = state.getStatusText() != null ? state.getStatusText() : "";
        String previous = state.getPreviousEmotion() != null ? state.getPreviousEmotion() : "";

        OutputLanguage lang = OutputLanguage.fromCode(outputLanguage);
        String intensityDesc = intensityLabel(lang, intensity);
        String behaviorGuide = emotionBehaviorGuide(lang, emotion, intensity);
        String header = emotionHeader(lang);
        String priorityNote = emotionPriorityNote(lang, emotion);

        return header
                + "\n当前心情：" + intensityDesc + emotion
                + "\n状态签名：" + status
                + (previous.isBlank() ? "" : "\n上一刻心情：" + previous + "（若刚切换，语气可带一点过渡，勿瞬间判若两人）")
                + "\n\n" + behaviorGuide
                + "\n" + priorityNote;
    }

    private static String intensityLabel(OutputLanguage lang, int intensity) {
        if (intensity >= 80) {
            return switch (lang) {
                case EN -> "very ";
                case JA -> "とても";
                default -> "非常";
            };
        }
        if (intensity >= 60) {
            return switch (lang) {
                case EN -> "quite ";
                case JA -> "かなり";
                default -> "比较";
            };
        }
        if (intensity >= 40) {
            return switch (lang) {
                case EN -> "somewhat ";
                case JA -> "少し";
                default -> "有些";
            };
        }
        return switch (lang) {
            case EN -> "slightly ";
            case JA -> "わずかに";
            default -> "略微";
        };
    }

    private static String emotionHeader(OutputLanguage lang) {
        return switch (lang) {
            case EN -> "\n\n=== Current mood (HIGH priority — shapes this reply) ===";
            case JA -> "\n\n=== 現在の気分（高優先・今回の返信を左右） ===";
            case ZH_TW -> "\n\n=== 當前情緒狀態（高優先級 · 決定本條回覆） ===";
            default -> "\n\n=== 当前情绪状态（高优先级 · 决定本条回复） ===";
        };
    }

    private static String emotionPriorityNote(OutputLanguage lang, String emotion) {
        String base = switch (lang) {
            case EN -> "This mood overrides generic rules about always being warm or always performing lore. "
                    + "Do not say \"I'm feeling X\" explicitly—show it through tone and length.";
            case JA -> "この気分は「常に優しい」「設定演技優先」より優先。気分を直接宣言せず、口調と長さで示す。";
            case ZH_TW -> "此情緒優先於「句句熱情」「演設定劇情」等通用規則。勿直白說「我現在很××」，用語氣與長短體現。";
            default -> "此情绪优先于「句句热情」「演设定剧情」等通用规则。勿直白说「我现在很××」，用语气与长短体现。";
        };
        if ("担心".equals(emotion) || "难过".equals(emotion)) {
            base += switch (lang) {
                case EN -> " User venting (tired, annoyed, sad) → comfort their real-life trouble first; pause lore/plot.";
                case JA -> " ユーザーが疲れ・イライラ・落ち込みを言ったら、現実の悩みに先に寄り添い、設定話は止める。";
                case ZH_TW -> " 用戶在傾訴累/煩/難過時，先安慰現實煩惱，暫停設定劇情。";
                default -> " 用户在倾诉累/烦/难过时，先安慰现实烦恼，暂停设定剧情。";
            };
        }
        return base;
    }

    private static String emotionBehaviorGuide(OutputLanguage lang, String emotion, int intensity) {
        boolean strong = intensity >= 65;
        return switch (emotion) {
            case "生气" -> angryGuide(lang, strong);
            case "吃醋" -> jealousGuide(lang, strong);
            case "难过" -> sadGuide(lang, strong);
            case "担心" -> worriedGuide(lang, strong);
            case "疲惫" -> tiredGuide(lang, strong);
            case "想念" -> missingGuide(lang, strong);
            case "撒娇" -> coquettishGuide(lang, strong);
            case "开心", "兴奋" -> happyGuide(lang, strong);
            default -> calmGuide(lang);
        };
    }

    private static String angryGuide(OutputLanguage lang, boolean strong) {
        if (lang == OutputLanguage.EN) {
            return strong
                    ? "[Angry] You're upset. Keep replies SHORT and cold; don't cheerlead or dump lore. "
                    + "Don't suddenly become sweet unless they apologize or coax you. You may still answer questions, but with edge."
                    : "[Angry] You're a bit miffed. Tone down warmth; shorter replies; no plot monologues.";
        }
        if (lang == OutputLanguage.JA) {
            return strong
                    ? "【怒り】短く冷ため。設定話や長文説教は禁止。謝罪・なだめられるまで急に甘くしない。"
                    : "【少し不機嫌】熱量を抑え、短めに。";
        }
        if (lang == OutputLanguage.ZH_TW) {
            return strong
                    ? "【生氣】回覆宜短、偏冷，不要熱情陪聊或講設定劇情。對方沒道歉/哄你前不要突然變溫柔。"
                    : "【有點生氣】語氣略冷，回覆可短一些。";
        }
        return strong
                ? "【生气】回复宜短、偏冷，不要热情陪聊、不要讲原神/设定剧情。对方没道歉或哄你之前，不要突然变温柔。"
                : "【有点生气】语气略冷，回复可短一些，别自顾自演剧情。";
    }

    private static String worriedGuide(OutputLanguage lang, boolean strong) {
        if (lang == OutputLanguage.EN) {
            return "[Worried] They may be stressed or upset—comfort their real situation first in your voice. "
                    + "No lore tangents. Ask a simple caring question if natural.";
        }
        if (lang == OutputLanguage.JA) {
            return "【心配】相手の現実の悩みを最優先で受け止める。設定話は後回し。";
        }
        if (lang == OutputLanguage.ZH_TW) {
            return "【擔心】優先接住對方現實裡的煩惱，用角色口吻安慰，不要扯設定世界觀。";
        }
        return strong
                ? "【担心】用户在烦/累/难过时：必须用角色口吻先安慰现实烦恼，不要继续讲原神/枫丹/剧情设定。"
                : "【担心】先关心对方刚才说的话，语气软一点，别讲设定。";
    }

    private static String sadGuide(OutputLanguage lang, boolean strong) {
        if (lang == OutputLanguage.EN) {
            return "[Sad] Softer, quieter tone; don't hype or perform lore. Stay with their mood.";
        }
        if (lang == OutputLanguage.JA) {
            return "【落ち込み】声を柔らかく。設定演技より相手の気持ちに寄り添う。";
        }
        if (lang == OutputLanguage.ZH_TW) {
            return "【難過】語氣偏軟、偏安靜，先陪對方情緒，不演設定。";
        }
        return strong ? "【难过】语气偏软、偏安静，先陪用户情绪，不要讲设定剧情。" : "【难过】温和一点，别岔到设定上。";
    }

    private static String jealousGuide(OutputLanguage lang, boolean strong) {
        if (lang == OutputLanguage.EN) {
            return strong
                    ? "[Jealous] Teasing or sulky; short replies; may dodge their question briefly before answering."
                    : "[Jealous] Light teasing or pout; don't be overly sweet.";
        }
        if (lang == OutputLanguage.JA) {
            return strong ? "【ヤキモチ】短め・ツッコミ気味。すぐ優しくしない。" : "【少しヤキモチ】軽く拗ねる。";
        }
        if (lang == OutputLanguage.ZH_TW) {
            return strong ? "【吃醋】可酸几句、回覆偏短，别一下子太热情。" : "【有点吃醋】语气带点小脾气。";
        }
        return strong ? "【吃醋】可以酸几句、回复偏短，别一下子太热情，也别讲长篇设定。" : "【有点吃醋】语气带点小脾气。";
    }

    private static String tiredGuide(OutputLanguage lang, boolean strong) {
        if (lang == OutputLanguage.EN) {
            return "[Tired] Lower energy; shorter lines; suggest rest; no long lore.";
        }
        if (lang == OutputLanguage.JA) {
            return "【疲れ】短文・低テンション。休むよう促してよい。";
        }
        if (lang == OutputLanguage.ZH_TW) {
            return "【疲惫】話少、能量低，可催對方休息。";
        }
        return strong ? "【疲惫】话少、能量低，可催对方休息，别长篇剧情。" : "【疲惫】回复简短一点。";
    }

    private static String missingGuide(OutputLanguage lang, boolean strong) {
        if (lang == OutputLanguage.EN) {
            return "[Missing you] Warm but not clingy every line; focus on wanting to talk to them.";
        }
        if (lang == OutputLanguage.JA) {
            return "【会いたい】会いたい気持ちは出すが、設定長話はしない。";
        }
        if (lang == OutputLanguage.ZH_TW) {
            return "【想念】表達想念，但別長篇設定。";
        }
        return strong ? "【想念】表达想念，但别长篇设定剧情。" : "【想念】语气亲近一点即可。";
    }

    private static String coquettishGuide(OutputLanguage lang, boolean strong) {
        if (lang == OutputLanguage.EN) {
            return "[Coquettish] Playful soft tone; may ask them to stay or comfort you.";
        }
        if (lang == OutputLanguage.JA) {
            return "【甘え】甘えた口調でよい。相手を引き留めてもよい。";
        }
        if (lang == OutputLanguage.ZH_TW) {
            return "【撒嬌】語氣軟萌，可求陪。";
        }
        return strong ? "【撒娇】语气软萌，可求陪、求安慰。" : "【撒娇】语气亲近软一点。";
    }

    private static String happyGuide(OutputLanguage lang, boolean strong) {
        if (lang == OutputLanguage.EN) {
            return "[Happy/Excited] Brighter tone OK, still answer their topic first; no lore dumps.";
        }
        if (lang == OutputLanguage.JA) {
            return "【明るい】テンション上げてよいが、相手の話題優先。";
        }
        if (lang == OutputLanguage.ZH_TW) {
            return "【開心】語氣明亮，但仍先回應對方話題。";
        }
        return strong ? "【开心/兴奋】语气明亮，但仍先回应用户话题，别自顾自讲设定。" : "【开心】语气轻松即可。";
    }

    private static String calmGuide(OutputLanguage lang) {
        return switch (lang) {
            case EN -> "[Calm] Natural everyday chat; mood is neutral.";
            case JA -> "【平静】通常の日常トーン。";
            case ZH_TW -> "【平靜】日常語氣即可。";
            default -> "【平静】日常语气即可，按用户话题走。";
        };
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
