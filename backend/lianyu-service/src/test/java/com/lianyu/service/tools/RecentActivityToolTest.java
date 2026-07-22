package com.lianyu.service.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lianyu.service.character.CharacterRecentActivityService;
import com.lianyu.service.support.OutputLanguageService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class RecentActivityToolTest {

    @AfterEach
    void tearDown() {
        ChatToolContext.clear();
    }

    @Test
    void getMyRecentLife_returnsFormattedBlock() {
        CharacterRecentActivityService activityService = mock(CharacterRecentActivityService.class);
        OutputLanguageService languageService = mock(OutputLanguageService.class);
        RecentActivityTool tool = new RecentActivityTool(activityService, languageService);

        ChatToolContext.set(3L, 5L, null);
        when(languageService.resolveForRequest(3L, null)).thenReturn("zh");
        when(activityService.formatForPrompt(3L, 5L, "zh"))
                .thenReturn("=== 你最近的生活动态 ===\n- 写了日记");

        assertThat(tool.getMyRecentLife()).contains("写了日记");
        verify(activityService).formatForPrompt(3L, 5L, "zh");
    }

    @Test
    void getMyRecentLife_emptyReturnsFallback() {
        CharacterRecentActivityService activityService = mock(CharacterRecentActivityService.class);
        OutputLanguageService languageService = mock(OutputLanguageService.class);
        RecentActivityTool tool = new RecentActivityTool(activityService, languageService);

        ChatToolContext.set(3L, 5L, null);
        when(languageService.resolveForRequest(3L, null)).thenReturn("zh");
        when(activityService.formatForPrompt(3L, 5L, "zh")).thenReturn("");

        assertThat(tool.getMyRecentLife()).contains("没有日记或动态");
    }
}
