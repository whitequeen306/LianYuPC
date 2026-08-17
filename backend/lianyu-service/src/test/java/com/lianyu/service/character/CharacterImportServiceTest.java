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
        request.setUserAddressing("笨蛋");

        BusinessException ex = assertThrows(BusinessException.class, () -> service.analyze(9L, request));

        assertEquals(ErrorCode.AI_PROVIDER_ERROR, ex.getErrorCode());
        assertEquals("未配置文本模型，请在设置中添加", ex.getMessage());
        verify(aiChatService, never()).analyzeCharacterImportWithVault(any(), any(), any());
    }

    @Test
    void usesPreferredUserVaultForExtraction() {
        VaultEntryResponse vault = VaultEntryResponse.builder()
                .id(3L)
                .provider("MyDeepSeek")
                .modelDefault("deepseek-chat")
                .build();
        when(vaultService.resolvePreferredUserVault(9L)).thenReturn(vault);
        when(aiChatService.analyzeCharacterImportWithVault(eq(vault), any(), eq("笨蛋")))
                .thenReturn(Map.of("name", "邻家姐姐"));

        AnalyzeCharacterImportRequest request = new AnalyzeCharacterImportRequest();
        request.setSourceText("你是一个温柔的邻家姐姐。");
        request.setUserAddressing("笨蛋");

        Map<String, Object> draft = service.analyze(9L, request);

        assertEquals("邻家姐姐", draft.get("name"));
        verify(aiChatService).analyzeCharacterImportWithVault(eq(vault), any(), eq("笨蛋"));
    }
}
