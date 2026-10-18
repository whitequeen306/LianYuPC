package com.lianyu.service.ai;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lianyu.dao.entity.Character;
import com.lianyu.service.character.CharacterChatBehavior;
import com.lianyu.service.character.CharacterChatBehaviorResolver;
import com.lianyu.service.character.CharacterStateService;
import com.lianyu.service.rules.PromptRuleEngine;
import com.lianyu.service.rules.hooks.GroupChatRuleHook;
import com.lianyu.service.rules.hooks.OutputLanguageRuleHook;
import com.lianyu.service.rules.hooks.ReplyBehaviorRuleHook;
import com.lianyu.service.tools.ToolManager;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * `buildSystemPrompt` 编排 golden（数据块以固定字符串注入）。
 *
 * <p>这一层要验的不是文本（文本已逐段迁完），而是**拼接顺序与分支**：
 * <pre>
 *   base(模板或默认骨架)
 *   → [userAddressing]
 *   → [记忆块 + 记忆优先级说明 + 称呼规则]
 *   → [工具提示]
 *   → 回复规则 → 输出语言规则 → [情绪块] → 安全边界
 * </pre>
 * 其中情绪块**故意在回复规则之后**（权重更高，避免被「别每句安慰」盖掉），
 * 这个顺序语义必须被锁住。
 *
 * <p>记忆/关系/情绪用固定字符串注入，把「数据从哪来」留到下一步单独解决。
 */
class SystemPromptOrchestrationGoldenTest {

    private static final String TOOL_HINT = "[[TOOL_HINT]]";
    private static final String EMOTION_BLOCK = "\n\n[[EMOTION_BLOCK]]";
    private static final String MEMORY_WITH_NAME = "【长期记忆/姓名】小林\n【长期记忆/爱好】听雨声";

    private static void setHumanize(Object hook) throws Exception {
        Field field = hook.getClass().getDeclaredField("humanizeEnabled");
        field.setAccessible(true);
        field.set(hook, true);
    }

    private static CharacterPromptBuilder newBuilder() throws Exception {
        ReplyBehaviorRuleHook reply = new ReplyBehaviorRuleHook();
        OutputLanguageRuleHook output = new OutputLanguageRuleHook();
        GroupChatRuleHook group = new GroupChatRuleHook();
        setHumanize(reply);
        setHumanize(group);
        PromptRuleEngine engine = new PromptRuleEngine(List.of(reply, output, group));

        ToolManager toolManager = mock(ToolManager.class);
        when(toolManager.buildToolsPromptHint(org.mockito.ArgumentMatchers.any())).thenReturn(TOOL_HINT);

        CharacterStateService stateService = mock(CharacterStateService.class);
        when(stateService.buildEmotionBlock(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(EMOTION_BLOCK);

        CharacterChatBehaviorResolver behaviorResolver = mock(CharacterChatBehaviorResolver.class);
        when(behaviorResolver.resolve(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new CharacterChatBehavior(2, false, 0, 0.0, 0, 0, ""));

        return new CharacterPromptBuilder(behaviorResolver, toolManager, engine, stateService);
    }

    private static Character character() {
        Character character = new Character();
        character.setId(3L);
        character.setOwnerUserId(7L);
        character.setName("小林");
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("personality", "温柔但有点固执");
        character.setSettings(settings);
        return character;
    }

    @Test
    void dumpOrchestrationGolden() throws Exception {
        CharacterPromptBuilder builder = newBuilder();
        Map<String, Object> cases = new LinkedHashMap<>();

        for (String lang : new String[]{"zh", "en"}) {
            for (boolean tools : new boolean[]{true, false}) {
                for (boolean inner : new boolean[]{true, false}) {
                    for (boolean withMemory : new boolean[]{true, false}) {
                        String key = lang + "|tools=" + tools + "|inner=" + inner
                                + "|memory=" + withMemory;
                        String prompt = builder.buildSystemPrompt(
                                character(),
                                withMemory ? MEMORY_WITH_NAME : "",
                                lang, tools, inner, 7L);
                        cases.put(key, prompt);
                    }
                }
            }
        }

        // 用户称呼规则（buildUserAddressingRule 的完整措辞，四语种）
        Map<String, String> addressing = new LinkedHashMap<>();
        String addressingMemory = "【长期记忆/姓名】{{NAME}}";
        for (String lang : new String[]{"zh", "zh-TW", "ja", "en"}) {
            java.lang.reflect.Method m = CharacterPromptBuilder.class.getDeclaredMethod(
                    "buildUserAddressingRule", String.class, String.class);
            m.setAccessible(true);
            addressing.put(lang, (String) m.invoke(builder, addressingMemory, lang));
        }
        Map<String, Object> addressingOut = new LinkedHashMap<>();
        addressingOut.put("namePlaceholder", "{{NAME}}");
        addressingOut.put("blocks", addressing);
        Path addressingTarget = Path.of("..", "..", "backend-agent", "app", "prompts",
                "addressing_templates.json");
        Files.createDirectories(addressingTarget.getParent());
        Files.writeString(addressingTarget, new ObjectMapper().writerWithDefaultPrettyPrinter()
                .writeValueAsString(addressingOut) + "\n", StandardCharsets.UTF_8);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("toolHint", TOOL_HINT);
        out.put("emotionBlock", EMOTION_BLOCK);
        out.put("memoryWithName", MEMORY_WITH_NAME);
        out.put("cases", cases);

        Path target = Path.of("..", "..", "backend-agent", "app", "prompts",
                "system_prompt_golden.json");
        Files.createDirectories(target.getParent());
        Files.writeString(target, new ObjectMapper().writerWithDefaultPrettyPrinter()
                .writeValueAsString(out) + "\n", StandardCharsets.UTF_8);
        System.out.println("system prompt golden cases: " + cases.size());
    }
}
