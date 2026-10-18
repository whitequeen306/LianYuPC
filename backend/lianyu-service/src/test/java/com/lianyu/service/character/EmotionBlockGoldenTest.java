package com.lianyu.service.character;

import com.lianyu.common.i18n.OutputLanguage;
import com.lianyu.dao.entity.CharacterState;
import com.lianyu.service.ai.ApiKeyVaultService;
import com.lianyu.service.support.OutputLanguageService;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 情绪块 golden：强度 × 语言 × 情绪 × 有无上一刻心情 × 空状态签名。
 *
 * <p>覆盖点：
 * <ul>
 *   <li>强度四档（>=80 / >=60 / >=40 / else）—— 文案是「非常/比较/有些/略微」+ 情绪词</li>
 *   <li>行为指南的 strong 阈值（intensity >= 65）与强度档位**不同**，容易混</li>
 *   <li>担心/难过 会追加「先安慰现实烦恼」的优先级说明</li>
 *   <li>上一刻心情为空时整行消失（不是留空行）</li>
 * </ul>
 */
class EmotionBlockGoldenTest {

    private static CharacterState state(String emotion, Integer intensity,
                                        String status, String previous) {
        CharacterState state = new CharacterState();
        state.setCurrentEmotion(emotion);
        state.setEmotionIntensity(intensity);
        state.setStatusText(status);
        state.setPreviousEmotion(previous);
        return state;
    }

    private static String invokeStatic(String name, OutputLanguage lang) throws Exception {
        java.lang.reflect.Method m = CharacterStateService.class.getDeclaredMethod(
                name, OutputLanguage.class);
        m.setAccessible(true);
        return (String) m.invoke(null, lang);
    }

    private static String invokeStaticInt(String name, OutputLanguage lang, int value) throws Exception {
        java.lang.reflect.Method m = CharacterStateService.class.getDeclaredMethod(
                name, OutputLanguage.class, int.class);
        m.setAccessible(true);
        return (String) m.invoke(null, lang, value);
    }

    private static String invokeStaticStr(String name, OutputLanguage lang, String value) throws Exception {
        java.lang.reflect.Method m = CharacterStateService.class.getDeclaredMethod(
                name, OutputLanguage.class, String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, lang, value);
    }

    private static String invokeGuide(OutputLanguage lang, String emotion, int intensity) throws Exception {
        java.lang.reflect.Method m = CharacterStateService.class.getDeclaredMethod(
                "emotionBehaviorGuide", OutputLanguage.class, String.class, int.class);
        m.setAccessible(true);
        return (String) m.invoke(null, lang, emotion, intensity);
    }

    @Test
    void dumpEmotionGolden() throws Exception {
        Map<String, Object> cases = new LinkedHashMap<>();
        String[] emotions = {"平静", "生气", "吃醋", "难过", "担心", "疲惫", "想念", "撒娇", "开心", "兴奋", "未知情绪"};
        int[] intensities = {10, 39, 40, 59, 60, 64, 65, 79, 80, 100};
        OutputLanguage[] langs = {OutputLanguage.ZH, OutputLanguage.ZH_TW,
                OutputLanguage.JA, OutputLanguage.EN};

        for (OutputLanguage lang : langs) {
            for (String emotion : emotions) {
                for (int intensity : intensities) {
                    String key = lang.name() + "|" + emotion + "|" + intensity;
                    cases.put(key, CharacterStateService.formatEmotionBlock(
                            state(emotion, intensity, "今天有点累", ""), lang));
                }
            }
            // 空状态签名 + 有上一刻心情
            cases.put(lang.name() + "|prev|empty-status", CharacterStateService.formatEmotionBlock(
                    state("难过", 70, null, "开心"), lang));
            // 全空 state：走默认值分支（平静 / 50）
            cases.put(lang.name() + "|defaults", CharacterStateService.formatEmotionBlock(
                    new CharacterState(), lang));
        }

        // 同时导出表：Python 不做文本手抄，只查表 + 拼装
        Map<String, String> headers = new LinkedHashMap<>();
        Map<String, String> labels = new LinkedHashMap<>();
        Map<String, String> guides = new LinkedHashMap<>();
        Map<String, String> notes = new LinkedHashMap<>();
        for (OutputLanguage lang : langs) {
            headers.put(lang.name(), invokeStatic("emotionHeader", lang));
            for (int band : new int[]{90, 70, 50, 20}) {
                labels.put(lang.name() + "|" + band,
                        invokeStaticInt("intensityLabel", lang, band));
            }
            for (String emotion : emotions) {
                for (boolean strong : new boolean[]{true, false}) {
                    guides.put(lang.name() + "|" + emotion + "|" + strong,
                            invokeGuide(lang, emotion, strong ? 80 : 30));
                }
                notes.put(lang.name() + "|" + emotion,
                        invokeStaticStr("emotionPriorityNote", lang, emotion));
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cases", cases);
        out.put("headers", headers);
        out.put("labels", labels);
        out.put("guides", guides);
        out.put("notes", notes);
        Path target = Path.of("..", "..", "backend-agent", "app", "prompts",
                "emotion_golden.json");
        Files.createDirectories(target.getParent());
        Files.writeString(target, new ObjectMapper().writerWithDefaultPrettyPrinter()
                .writeValueAsString(out) + "\n", StandardCharsets.UTF_8);
        System.out.println("emotion golden cases: " + cases.size());
    }
}
