package com.lianyu.service.character;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lianyu.common.base.ErrorCode;
import com.lianyu.common.exception.BusinessException;
import com.lianyu.service.ai.AiChatService;
import com.lianyu.service.ai.ApiKeyVaultService;
import com.lianyu.service.dto.AnalyzeCharacterImportRequest;
import com.lianyu.service.dto.VaultEntryResponse;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CharacterImportServiceTest {

    @Mock
    private ApiKeyVaultService vaultService;

    @Mock
    private AiChatService aiChatService;

    @InjectMocks
    private CharacterImportService service;

    @Test
    void refusesWhenUserHasNoTextVault() {
        when(vaultService.resolvePreferredUserVault(9L)).thenReturn(null);
        AnalyzeCharacterImportRequest request = new AnalyzeCharacterImportRequest();
        request.setSourceText("你是一个温柔的邻家姐姐。");

        BusinessException ex = assertThrows(BusinessException.class, () -> service.analyze(9L, request));

        assertEquals(ErrorCode.AI_PROVIDER_ERROR, ex.getErrorCode());
        assertEquals("未配置文本模型，请在设置中添加", ex.getMessage());
        verify(aiChatService, never()).analyzeCharacterImportWithVault(any(), any());
    }

    @Test
    void usesPreferredUserVaultAndExtractedAddressing() {
        VaultEntryResponse vault = VaultEntryResponse.builder()
                .id(3L)
                .provider("MyDeepSeek")
                .modelDefault("deepseek-chat")
                .build();
        when(vaultService.resolvePreferredUserVault(9L)).thenReturn(vault);
        when(aiChatService.analyzeCharacterImportWithVault(eq(vault), any()))
                .thenReturn(new HashMap<>(Map.of(
                        "name", "邻家姐姐",
                        "promptTemplate", "性格定位：温柔",
                        "userAddressing", "「笨蛋」")));

        AnalyzeCharacterImportRequest request = new AnalyzeCharacterImportRequest();
        request.setSourceText("你是一个温柔的邻家姐姐。笨蛋，过来。");

        Map<String, Object> draft = service.analyze(9L, request);

        assertEquals("邻家姐姐", draft.get("name"));
        assertEquals("笨蛋", draft.get("userAddressing"));
        assertEquals(true, String.valueOf(draft.get("promptTemplate")).contains("最常用的称呼是「笨蛋」"));
        verify(aiChatService).analyzeCharacterImportWithVault(eq(vault), any());
    }
}
