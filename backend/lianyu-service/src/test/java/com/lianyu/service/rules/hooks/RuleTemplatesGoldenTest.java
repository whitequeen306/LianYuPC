package com.lianyu.service.rules.hooks;

import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lianyu.service.rules.PromptRuleContext;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 生成「输出语言规则」与「群聊规则」模板（Java 真实渲染 + 占位符）。
 *
 * <p>与 ReplyRuleTemplateGoldenTest 同一思路：这两个 hook 同样是
 * **文本块 + 内联拼接**混写（群聊规则里 name/other/mention/maxPieces 都是拼接进去的），
 * 逐块迁移必须手抄内联片段，因此改为模板化：
 * <ul>
 *   <li>{@code {{NAME}}} / {@code {{OTHER}}} / {@code {{MENTION}}} / {@code {{PIECES}}}
 *       —— 动态位置占位</li>
 *   <li>Python 只做选择 + 替换，文本由构造保证一致</li>
 * </ul>
 *
 * <p>输出：{@code backend-agent/app/prompts/rule_templates.json}
 */
class RuleTemplatesGoldenTest {

    static final String NAME = "{{NAME}}";
    static final String OTHER = "{{OTHER}}";
    static final String MENTION = "{{MENTION}}";

    private static void setHumanize(Object hook) throws Exception {
        Field field = hook.getClass().getDeclaredField("humanizeEnabled");
        field.setAccessible(true);
        field.set(hook, true);
    }

    @Test
    void dumpOutputLanguageAndGroupTemplates() throws Exception {
        Map<String, String> outputLanguage = new LinkedHashMap<>();
        OutputLanguageRuleHook outputHook = new OutputLanguageRuleHook();
        for (String lang : new String[]{"ja", "en", "zh-TW", "zh"}) {
            outputLanguage.put(lang, outputHook.render(PromptRuleContext.forOutputLanguage(lang)));
        }

        Map<String, String> groupChat = new LinkedHashMap<>();
        GroupChatRuleHook groupHook = new GroupChatRuleHook();
        setHumanize(groupHook);
        for (String lang : new String[]{"ja", "en", "zh-TW", "zh"}) {
            for (int pieces : new int[]{1, 2}) {
                PromptRuleContext context = PromptRuleContext.forGroupChat(
                        lang, NAME, pieces, OTHER, MENTION, true);
                groupChat.put(lang + "|" + pieces + "|true", groupHook.render(context));
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("placeholders", Map.of("name", NAME, "other", OTHER,
                "mention", MENTION));
        out.put("outputLanguage", outputLanguage);
        out.put("groupChat", groupChat);

        Path target = Path.of("..", "..", "backend-agent", "app", "prompts",
                "rule_templates.json");
        Files.createDirectories(target.getParent());
        Files.writeString(target, new ObjectMapper().writerWithDefaultPrettyPrinter()
                .writeValueAsString(out) + "\n", StandardCharsets.UTF_8);
        System.out.println("rule templates: outputLanguage=" + outputLanguage.size()
                + ", groupChat=" + groupChat.size());
    }
}
