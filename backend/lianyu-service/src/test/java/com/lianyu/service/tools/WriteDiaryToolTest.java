package com.lianyu.service.tools;

import com.lianyu.dao.entity.CharacterDiary;
import com.lianyu.dao.mapper.CharacterDiaryMapper;
import com.lianyu.service.ai.ApiKeyVaultService;
import com.lianyu.service.ai.background.AiBackgroundPublisher;
import com.lianyu.service.ai.background.AiBackgroundTask;
import com.lianyu.service.dto.VaultEntryResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WriteDiaryToolTest {

    private ApiKeyVaultService apiKeyVaultService;
    private CharacterDiaryMapper diaryMapper;
    private AiBackgroundPublisher aiBackgroundPublisher;
    private WriteDiaryTool tool;

    @BeforeEach
    void setUp() {
        apiKeyVaultService = mock(ApiKeyVaultService.class);
        diaryMapper = mock(CharacterDiaryMapper.class);
        aiBackgroundPublisher = mock(AiBackgroundPublisher.class);
        tool = new WriteDiaryTool(apiKeyVaultService, diaryMapper, aiBackgroundPublisher);
        ReflectionTestUtils.setField(tool, "impromptuMaxPerDay", 1);
        ChatToolContext.set(10L, 100L, null);
    }

    @AfterEach
    void tearDown() {
        ChatToolContext.clear();
    }

    @Test
    void writeDiary_happyPath_enqueuesImpromptuTask() {
        VaultEntryResponse vault = new VaultEntryResponse();
        vault.setProvider("deepseek");
        when(apiKeyVaultService.resolvePreferredUserVault(10L)).thenReturn(vault);
        when(diaryMapper.selectCount(any())).thenReturn(0L);

        String result = tool.writeDiary();

        assertTrue(result.contains("日记"));
        ArgumentCaptor<AiBackgroundTask> captor = ArgumentCaptor.forClass(AiBackgroundTask.class);
        verify(aiBackgroundPublisher).publish(captor.capture());
        AiBackgroundTask task = captor.getValue();
        assertEquals(10L, task.userId());
        assertEquals(100L, task.characterId());
        assertEquals(CharacterDiary.SOURCE_IMPROMPTU, task.source());
    }

    @Test
    void writeDiary_noUserVault_refusesWithoutEnqueue() {
        when(apiKeyVaultService.resolvePreferredUserVault(10L)).thenReturn(null);

        String result = tool.writeDiary();

        assertTrue(result.contains("未配置文本模型"));
        verify(aiBackgroundPublisher, never()).publish(any(AiBackgroundTask.class));
    }

    @Test
    void writeDiary_quotaReached_refusesWithoutEnqueue() {
        VaultEntryResponse vault = new VaultEntryResponse();
        vault.setProvider("deepseek");
        when(apiKeyVaultService.resolvePreferredUserVault(10L)).thenReturn(vault);
        when(diaryMapper.selectCount(any())).thenReturn(1L); // 当天已有 1 篇即兴

        String result = tool.writeDiary();

        assertTrue(result.contains("已经写过日记"));
        verify(aiBackgroundPublisher, never()).publish(any(AiBackgroundTask.class));
    }

    @Test
    void writeDiary_enqueueFailed_returnsGracefulText() {
        VaultEntryResponse vault = new VaultEntryResponse();
        vault.setProvider("deepseek");
        when(apiKeyVaultService.resolvePreferredUserVault(10L)).thenReturn(vault);
        when(diaryMapper.selectCount(any())).thenReturn(0L);
        org.mockito.Mockito.doThrow(new RuntimeException("mq down"))
                .when(aiBackgroundPublisher).publish(any(AiBackgroundTask.class));

        String result = tool.writeDiary();

        assertTrue(result.contains("没能记下来"));
    }
}
