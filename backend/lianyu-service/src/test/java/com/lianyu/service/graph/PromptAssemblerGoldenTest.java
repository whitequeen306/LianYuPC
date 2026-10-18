package com.lianyu.service.graph;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lianyu.service.tools.TimeTool;
import com.lianyu.dao.entity.Character;
import com.lianyu.service.ai.CharacterPromptBuilder;
import com.lianyu.service.character.CharacterChatBehaviorResolver;
import com.lianyu.service.conversation.ProactiveRealWorldContextService;
import com.lianyu.service.support.OutputLanguageService;
import com.lianyu.service.memory.MemoryRetriever;
import com.lianyu.service.relationship.RelationshipStateService;
import com.lianyu.service.conversation.SessionSummaryService;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Golden 生成器：把 Java 侧「纯文本段落」的组装结果落盘，供 Python 逐字节比对。
 *
 * <p>为什么需要它：P5.5 要把 system prompt 组装迁到 Python。如果只在 Python 里
 * 用同样的片段按同样顺序拼一遍再断言相等，那是**循环验证**——两边都错也会通过。
 * 必须由 Java 真实执行一次，产出基准。
 *
 * <p>覆盖范围：时间锚点、城市权威段（这两段只依赖片段 + 少量参数）。
 * 晚安模式依赖「当前是否夜里」的真实时钟，不适合快照，其片段已在
 * `fragments.json` 里单独锁定。
 *
 * <p>输出：`backend-agent/app/prompts/assembler_golden.json`
 * （mvn test 的 cwd 是模块目录，因此路径为 `../backend-agent/...`）
 */
class PromptAssemblerGoldenTest {

    private static final String BASE = "BASE_PROMPT";

    private ChatTurnPromptAssembler newAssembler(TimeTool timeTool) {
        return new ChatTurnPromptAssembler(
                mock(CharacterPromptBuilder.class),
                mock(MemoryRetriever.class),
                mock(RelationshipStateService.class),
                mock(SessionSummaryService.class),
                mock(OutputLanguageService.class),
                timeTool,
                mock(CharacterChatBehaviorResolver.class),
                mock(ProactiveRealWorldContextService.class));
    }

    private static String invoke(ChatTurnPromptAssembler assembler, String name,
                                 Class<?>[] types, Object... args) throws Exception {
        Method method = ChatTurnPromptAssembler.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        return (String) method.invoke(assembler, args);
    }

    @Test
    void dumpGoldenPrompts() throws Exception {
        TimeTool timeTool = mock(TimeTool.class);
        String timeFact = "当前真实时间：2026-10-18 22:00:00（周日）\n当前时段：晚上（21:00-23:00）";
        when(timeTool.readCurrentTimeFact()).thenReturn(timeFact);
        ChatTurnPromptAssembler assembler = newAssembler(timeTool);

        String timeContext = invoke(assembler, "appendCurrentTimeContext",
                new Class<?>[]{String.class}, BASE);

        Character character = new Character();
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("city_mode", "real");
        settings.put("city", "上海");
        character.setSettings(settings);
        String cityContext = invoke(assembler, "appendCurrentRealCityContext",
                new Class<?>[]{String.class, Character.class}, BASE, character);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("base", BASE);
        out.put("timeFact", timeFact);
        out.put("timeContext", timeContext);
        out.put("city", "上海");
        out.put("cityContext", cityContext);

        // 自然口语风格块（四语种 × 是否显示内心独白）：来自 OutputLanguageService 的真实 switch
        com.lianyu.service.support.OutputLanguageService languageService =
                new com.lianyu.service.support.OutputLanguageService(
                        mock(org.springframework.data.redis.core.StringRedisTemplate.class));
        Map<String, String> naturalStyle = new LinkedHashMap<>();
        for (String lang : new String[]{"ja", "en", "zh-TW", "zh"}) {
            for (boolean inner : new boolean[]{true, false}) {
                naturalStyle.put(lang + "|" + inner,
                        languageService.buildNaturalStyleBlock(lang, inner));
            }
        }
        out.put("naturalStyleBlocks", naturalStyle);

        Path target = Path.of("..", "..", "backend-agent", "app", "prompts", "assembler_golden.json");
        Files.createDirectories(target.getParent());
        Files.writeString(target, new ObjectMapper().writerWithDefaultPrettyPrinter()
                .writeValueAsString(out) + "\n", StandardCharsets.UTF_8);
        System.out.println("golden written: " + target.toAbsolutePath());
    }
}
