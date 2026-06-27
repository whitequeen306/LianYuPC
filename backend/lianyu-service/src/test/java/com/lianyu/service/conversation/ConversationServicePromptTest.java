package com.lianyu.service.conversation;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.lianyu.dao.entity.Character;
import com.lianyu.service.ai.CharacterPromptBuilder;
import com.lianyu.service.character.CharacterRecentActivityService;
import com.lianyu.service.support.OutputLanguageService;
import com.lianyu.service.tools.TimeTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class ConversationServicePromptTest {

    private CharacterRecentActivityService activityService;
    private OutputLanguageService outputLanguageService;
    private CharacterPromptBuilder promptBuilder;
    private TimeTool timeTool;
    private ConversationService conversationService;

    @BeforeEach
    void setUp() {
        activityService = mock(CharacterRecentActivityService.class);
        outputLanguageService = mock(OutputLanguageService.class);
        promptBuilder = mock(CharacterPromptBuilder.class);
        timeTool = mock(TimeTool.class);

        conversationService = new ConversationService(
                null,
                null,
                null,
                null,
                null,
                null,
                promptBuilder,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                outputLanguageService,
                null,
                null,
                null,
                null,
                timeTool,
                null,
                activityService);

        when(activityService.formatForPrompt(eq(9L), eq(8L), eq("zh")))
                .thenReturn("=== 你最近的生活动态（背景记忆，勿逐条复述） ===\n- 6月22日：写了日记《测试》");
        when(outputLanguageService.resolveForRequest(9L, "你好")).thenReturn("zh");
        when(promptBuilder.buildSystemPrompt(any(Character.class), eq(""), eq("zh"), eq(true)))
                .thenReturn("BASE_PROMPT");
        when(timeTool.readCurrentTimeFact()).thenReturn("2026-06-22 10:00");
    }

    @Test
    void buildSystemPromptForUser_includesRecentActivityBlock() {
        Character character = new Character();
        character.setId(8L);
        character.setSettings(null);

        String prompt = ReflectionTestUtils.invokeMethod(
                conversationService,
                "buildSystemPromptForUser",
                9L,
                character,
                "",
                "你好");

        assertTrue(prompt.contains("=== 你最近的生活动态"));
        assertTrue(prompt.contains("写了日记"));
    }
}
