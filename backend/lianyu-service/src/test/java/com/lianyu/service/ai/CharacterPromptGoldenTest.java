package com.lianyu.service.ai;

import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lianyu.service.character.CharacterChatBehaviorResolver;
import com.lianyu.service.character.CharacterStateService;
import com.lianyu.service.tools.ToolManager;
import com.lianyu.service.rules.PromptRuleEngine;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 生成「人设提取 / 默认提示词」的 golden（Java 真实执行）。
 *
 * <p>这两个方法是 `buildSystemPrompt` 的前置：前者把 `character.settings`
 * 压成 persona 文本，后者在无自定义模板时给出骨架。它们纯函数、无 IO，
 * 但**分支不少**（personality 长度阈值 20、是否已含「说话风格/背景」、
 * age 是否存在），逐分支比对成本很低，漏一个分支就是真实差异。
 *
 * <p>输出：`backend-agent/app/prompts/character_prompt_golden.json`
 */
class CharacterPromptGoldenTest {

    private static CharacterPromptBuilder newBuilder() {
        return new CharacterPromptBuilder(
                mock(CharacterChatBehaviorResolver.class),
                mock(ToolManager.class),
                mock(PromptRuleEngine.class),
                mock(CharacterStateService.class));
    }

    private static Object invoke(CharacterPromptBuilder builder, String name,
                                 Class<?>[] types, Object... args) throws Exception {
        Method method = CharacterPromptBuilder.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method.invoke(builder, args);
    }

    private static Map<String, Object> settings(String personality, String style,
                                                String backstory, Object age) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (personality != null) {
            map.put("personality", personality);
        }
        if (style != null) {
            map.put("speakingStyle", style);
        }
        if (backstory != null) {
            map.put("backstory", backstory);
        }
        if (age != null) {
            map.put("age", age);
        }
        return map;
    }

    @Test
    void dumpCharacterPromptGolden() throws Exception {
        CharacterPromptBuilder builder = newBuilder();
        Map<String, Object> cases = new LinkedHashMap<>();

        Object[][] matrix = new Object[][]{
                // key, settings, name
                {"null-settings", null, "小林"},
                {"empty-settings", new LinkedHashMap<String, Object>(), "小林"},
                {"long-personality-only", settings("她是一个温柔又固执的人，喜欢在雨天听歌，讨厌被打断。",
                        null, null, null), "小林"},
                {"long-personality-with-style", settings("她是一个温柔又固执的人，喜欢在雨天听歌，讨厌被打断。",
                        "说话简短，多用省略号", null, null), "小林"},
                {"long-personality-mentions-style", settings("她是一个温柔的人。说话风格：轻快；背景：乡下长大，爱做饭。",
                        "不应被追加", "不应被追加", null), "小林"},
                {"short-personality-full", settings("温柔",
                        "说话带点撒娇", "小时候养过一只猫", 22), "小林"},
                {"boundary-19", settings("一二三四五六七八九十一二三四五六七八九",
                        null, null, null), "小林"},
                {"boundary-20", settings("一二三四五六七八九十一二三四五六七八九十",
                        null, null, null), "小林"},
        };

        for (Object[] row : matrix) {
            String key = (String) row[0];
            @SuppressWarnings("unchecked")
            Map<String, Object> s = (Map<String, Object>) row[1];
            String name = (String) row[2];
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("persona", invoke(builder, "extractPersona",
                    new Class<?>[]{String.class, Map.class}, name, s));
            entry.put("defaultPrompt", invoke(builder, "buildDefaultPrompt",
                    new Class<?>[]{String.class, String.class}, name,
                    entry.get("persona")));
            cases.put(key, entry);
        }

        Path target = Path.of("..", "..", "backend-agent", "app", "prompts",
                "character_prompt_golden.json");
        Files.createDirectories(target.getParent());
        Files.writeString(target, new ObjectMapper().writerWithDefaultPrettyPrinter()
                .writeValueAsString(cases) + "\n", StandardCharsets.UTF_8);
        System.out.println("character prompt golden cases: " + cases.size());
    }
}
