package com.lianyu.service.rules.hooks;

import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lianyu.service.character.CharacterChatBehavior;
import com.lianyu.service.rules.PromptRuleContext;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 生成「回复规则」模板（按 语言 × 内心独白 × 气泡数 展开），供 Python 逐字节复用。
 *
 * <p>为什么不逐块移植：`ReplyBehaviorRuleHook.renderZh` 等方法里大量是
 * **内联拼接**（`"4. 可拆成1~" + maxPieces + "条短消息…"`、`innerThoughtRuleZh(...)`、
 * `humanizeBlockZh()`），文本块只占一部分。逐块迁移必须手工拷贝这些内联片段，
 * 而手抄正是本轮反复强调要避免的风险。
 *
 * <p>因此改为：让 Java 真实渲染出完整规则串，persona 位置用占位符
 * {@code {{PERSONA}}} 代替，Python 运行时替换即可 —— 文本**由构造保证一致**。
 *
 * <p>组合空间极小：4 语种 × 2（内心独白）× 2（多气泡/单气泡）= 16 条模板。
 * humanize 固定为 true（生产默认值）。
 */
class ReplyRuleTemplateGoldenTest {

    static final String PERSONA_PLACEHOLDER = "{{PERSONA}}";

    private static ReplyBehaviorRuleHook newHook() throws Exception {
        ReplyBehaviorRuleHook hook = new ReplyBehaviorRuleHook();
        Field field = ReplyBehaviorRuleHook.class.getDeclaredField("humanizeEnabled");
        field.setAccessible(true);
        field.set(hook, true);
        return hook;
    }

    @Test
    void dumpReplyRuleTemplates() throws Exception {
        ReplyBehaviorRuleHook hook = newHook();
        Map<String, String> templates = new LinkedHashMap<>();

        for (String lang : new String[]{"ja", "en", "zh-TW", "zh"}) {
            for (boolean inner : new boolean[]{true, false}) {
                for (int pieces : new int[]{1, 2}) {
                    // 记录字段顺序: int,boolean,int,double,int,int,String
                    CharacterChatBehavior behavior = new CharacterChatBehavior(
                            pieces, false, 0, 0.0, 0, 0, "");
                    PromptRuleContext context = PromptRuleContext.forReply(
                            lang, PERSONA_PLACEHOLDER, behavior, inner);
                    String key = lang + "|" + inner + "|" + pieces;
                    templates.put(key, hook.render(context));
                }
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("personaPlaceholder", PERSONA_PLACEHOLDER);
        out.put("humanizeEnabled", true);
        out.put("templates", templates);

        Path target = Path.of("..", "..", "backend-agent", "app", "prompts",
                "reply_rule_templates.json");
        Files.createDirectories(target.getParent());
        Files.writeString(target, new ObjectMapper().writerWithDefaultPrettyPrinter()
                .writeValueAsString(out) + "\n", StandardCharsets.UTF_8);
        System.out.println("reply templates written: " + templates.size());
    }
}
