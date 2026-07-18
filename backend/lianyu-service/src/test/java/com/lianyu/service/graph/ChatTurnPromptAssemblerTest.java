package com.lianyu.service.graph;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.lianyu.ai.graph.ChatTurnScene;
import com.lianyu.dao.entity.Character;
import com.lianyu.service.ai.CharacterPromptBuilder;
import com.lianyu.service.character.CharacterChatBehaviorResolver;
import com.lianyu.service.character.CharacterRecentActivityService;
import com.lianyu.service.conversation.ProactiveRealWorldContextService;
import com.lianyu.service.conversation.SessionSummaryService;
import com.lianyu.service.memory.MemoryRetriever;
import com.lianyu.service.relationship.RelationshipStateService;
import com.lianyu.service.support.OutputLanguageService;
import com.lianyu.service.tools.TimeTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ChatTurnPromptAssemblerTest {

    private ChatTurnPromptAssembler assembler;
    private CharacterPromptBuilder promptBuilder;
    private CharacterRecentActivityService activityService;
    private OutputLanguageService outputLanguageService;
    private TimeTool timeTool;
    private MemoryRetriever memoryRetriever;
    private RelationshipStateService relationshipStateService;
    private SessionSummaryService sessionSummaryService;

    @BeforeEach
    void setUp() {
        promptBuilder = mock(CharacterPromptBuilder.class);
        activityService = mock(CharacterRecentActivityService.class);
        outputLanguageService = mock(OutputLanguageService.class);
        timeTool = mock(TimeTool.class);
        memoryRetriever = mock(MemoryRetriever.class);
        relationshipStateService = mock(RelationshipStateService.class);
        sessionSummaryService = mock(SessionSummaryService.class);

        assembler = new ChatTurnPromptAssembler(
                promptBuilder,
                memoryRetriever,
                relationshipStateService,
                sessionSummaryService,
                outputLanguageService,
                timeTool,
                activityService,
                mock(CharacterChatBehaviorResolver.class),
                mock(ProactiveRealWorldContextService.class));

        when(memoryRetriever.retrieveProfileContext(eq(8L), eq(9L), eq("你好"))).thenReturn("");
        when(relationshipStateService.buildPromptContext(9L, 8L)).thenReturn("");
        when(sessionSummaryService.formatForPrompt(any())).thenReturn("");
        when(activityService.formatForPrompt(eq(9L), eq(8L), eq("zh")))
                .thenReturn("=== 你最近的生活动态（背景记忆，勿逐条复述） ===\n- 6月22日：写了日记《测试》");
        when(outputLanguageService.resolveForRequest(9L, "你好")).thenReturn("zh");
        when(outputLanguageService.buildNaturalStyleBlock(eq("zh"), eq(true))).thenReturn("");
        when(promptBuilder.buildSystemPrompt(any(Character.class), eq(""), eq("zh"), eq(true)))
                .thenReturn("BASE_PROMPT");
        when(timeTool.readCurrentTimeFact()).thenReturn("2026-06-22 10:00");
    }

    @Test
    void assemble_single_includesRecentActivityBlock() {
        Character character = new Character();
        character.setId(8L);
        character.setSettings(null);

        ChatTurnPromptAssembler.AssembledPrompt prompt = assembler.assemble(
                ChatTurnScene.SINGLE,
                9L,
                1L,
                character,
                "你好",
                "你好",
                null,
                null);

        assertTrue(prompt.systemPrompt().contains("=== 你最近的生活动态"));
        assertTrue(prompt.systemPrompt().contains("写了日记"));
    }
}
