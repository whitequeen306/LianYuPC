package com.lianyu.service.graph;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lianyu.ai.graph.ChatTurnScene;
import com.lianyu.dao.entity.Character;
import com.lianyu.service.ai.CharacterPromptBuilder;
import com.lianyu.service.character.CharacterChatBehavior;
import com.lianyu.service.character.CharacterChatBehaviorResolver;
import com.lianyu.service.character.CharacterStateService;
import com.lianyu.service.conversation.ProactiveRealWorldContextService;
import com.lianyu.service.conversation.SessionSummaryService;
import com.lianyu.service.memory.MemoryRetriever;
import com.lianyu.service.relationship.RelationshipStateService;
import com.lianyu.service.rules.PromptRuleEngine;
import com.lianyu.service.rules.hooks.GroupChatRuleHook;
import com.lianyu.service.rules.hooks.OutputLanguageRuleHook;
import com.lianyu.service.rules.hooks.ReplyBehaviorRuleHook;
import com.lianyu.service.support.OutputLanguageService;
import com.lianyu.service.tools.TimeTool;
import com.lianyu.service.tools.ToolManager;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 端到端验收：`ChatTurnPromptAssembler.assemble` 产出的**完整 system prompt**。
 *
 * <p>前面各步都是分段验证（文本、规则、编排、记忆格式）。这里把它们串起来，
 * 与 Java 的真实总输出逐字节比对 —— 这是「积木拼成房子」的最后一道关。
 *
 * <p>数据块（记忆/关系/会话摘要）用固定字符串注入，因此校验的是
 * **拼接与顺序**，不含取数差异（那部分只能在 SHADOW 下用真实流量验证）。
 *
 * <p>userInput 传空串，避免「晚安模式」因运行时刻不同而产生非确定性输出。
 */
class AssemblerEndToEndGoldenTest {

    private static final String MEMORY_BLOCK = "[用户画像]\n- 【长期记忆/姓名】小林";
    private static final String RELATIONSHIP_BLOCK = "[[RELATIONSHIP]]";
    private static final String SESSION_BLOCK = "[[SESSION_SUMMARY]]";
    private static final String TIME_FACT = "当前真实时间：2026-10-18 15:00:00（周六）\n当前时段：下午（13:00-18:00）";

    private static void setHumanize(Object hook) throws Exception {
        Field field = hook.getClass().getDeclaredField("humanizeEnabled");
        field.setAccessible(true);
        field.set(hook, true);
    }

    private static ChatTurnPromptAssembler newAssembler() throws Exception {
        return newAssembler(new OutputLanguageService(mock(StringRedisTemplate.class)));
    }

    private static ChatTurnPromptAssembler newAssembler(OutputLanguageService languageService) throws Exception {
        ReplyBehaviorRuleHook reply = new ReplyBehaviorRuleHook();
        OutputLanguageRuleHook output = new OutputLanguageRuleHook();
        GroupChatRuleHook group = new GroupChatRuleHook();
        setHumanize(reply);
        setHumanize(group);
        PromptRuleEngine engine = new PromptRuleEngine(List.of(reply, output, group));

        ToolManager toolManager = mock(ToolManager.class);
        when(toolManager.buildToolsPromptHint(any())).thenReturn("[[TOOL_HINT]]");
        CharacterStateService stateService = mock(CharacterStateService.class);
        when(stateService.buildEmotionBlock(any(), any(), any())).thenReturn("\n\n[[EMOTION]]");
        CharacterChatBehaviorResolver behaviorResolver = mock(CharacterChatBehaviorResolver.class);
        when(behaviorResolver.resolve(any())).thenReturn(new CharacterChatBehavior(2, false, 0, 0.0, 0, 0, ""));

        CharacterPromptBuilder promptBuilder = new CharacterPromptBuilder(
                behaviorResolver, toolManager, engine, stateService);

        MemoryRetriever memoryRetriever = mock(MemoryRetriever.class);
        when(memoryRetriever.retrieveProfileContext(anyLong(), anyLong(), any()))
                .thenReturn(MEMORY_BLOCK);
        RelationshipStateService relationship = mock(RelationshipStateService.class);
        when(relationship.buildPromptContext(anyLong(), anyLong())).thenReturn(RELATIONSHIP_BLOCK);
        SessionSummaryService session = mock(SessionSummaryService.class);
        when(session.formatForPrompt(anyLong())).thenReturn(SESSION_BLOCK);

        TimeTool timeTool = mock(TimeTool.class);
        when(timeTool.readCurrentTimeFact()).thenReturn(TIME_FACT);

        return new ChatTurnPromptAssembler(
                promptBuilder, memoryRetriever, relationship, session, languageService,
                timeTool, behaviorResolver, mock(ProactiveRealWorldContextService.class));
    }

    private static Character character() {
        Character character = new Character();
        character.setId(3L);
        character.setOwnerUserId(7L);
        character.setName("小林");
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("personality", "温柔但有点固执");
        settings.put("city_mode", "real");
        settings.put("city", "上海");
        character.setSettings(settings);
        return character;
    }

    @Test
    void dumpEndToEndPrompt() throws Exception {
        ChatTurnPromptAssembler assembler = newAssembler();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("memoryBlock", MEMORY_BLOCK);
        out.put("relationshipBlock", RELATIONSHIP_BLOCK);
        out.put("sessionBlock", SESSION_BLOCK);
        out.put("timeFact", TIME_FACT);
        out.put("city", "上海");

        Map<String, String> prompts = new LinkedHashMap<>();
        for (String lang : new String[]{"zh", "en"}) {
            // 用 spy 固定语言：真实 buildNaturalStyleBlock 行为保留，只替换 resolveForRequest
            StringRedisTemplate redis = mock(StringRedisTemplate.class);
            @SuppressWarnings("unchecked")
            org.springframework.data.redis.core.ValueOperations<String, String> valueOps =
                    mock(org.springframework.data.redis.core.ValueOperations.class);
            when(redis.opsForValue()).thenReturn(valueOps);
            OutputLanguageService languageService = org.mockito.Mockito.spy(
                    new OutputLanguageService(redis));
            when(languageService.resolveForRequest(anyLong(), any())).thenReturn(lang);
            ChatTurnPromptAssembler langAssembler = newAssembler(languageService);
            String prompt = langAssembler.assemble(
                    ChatTurnScene.SINGLE, 7L, 42L, character(),
                    "", "今天好累", "", null).systemPrompt();
            prompts.put(lang, prompt);
        }
        out.put("prompts", prompts);

        Path target = Path.of("..", "..", "backend-agent", "app", "prompts",
                "end_to_end_golden.json");
        Files.createDirectories(target.getParent());
        Files.writeString(target, new ObjectMapper().writerWithDefaultPrettyPrinter()
                .writeValueAsString(out) + "\n", StandardCharsets.UTF_8);
        System.out.println("end-to-end prompts: " + prompts.size()
                + " (zh=" + prompts.get("zh").length() + " chars)");
    }
}
