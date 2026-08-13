package com.lianyu.service.tools.bridge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lianyu.service.ai.ApiKeyVaultService;
import com.lianyu.service.dto.VaultEntryResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EngineCredentialsServiceTest {

    @Mock
    private ApiKeyVaultService vaultService;

    @InjectMocks
    private EngineCredentialsService service;

    @Test
    void unavailableWhenUserHasNoTextVault() {
        when(vaultService.resolvePreferredUserVault(7L)).thenReturn(null);

        EngineCredentialsService.EngineCredentials creds = service.resolve(7L);

        assertThat(creds.available()).isFalse();
        assertThat(creds.apiKey()).isEmpty();
        assertThat(creds.baseUrl()).isEmpty();
        assertThat(creds.model()).isEmpty();
    }

    @Test
    void resolvesPreferredVaultAndDecryptsKey() {
        VaultEntryResponse vault = VaultEntryResponse.builder()
                .id(42L)
                .provider("MyDeepSeek")
                .baseUrl("https://api.deepseek.com")
                .modelDefault("deepseek-v4-flash")
                .build();
        when(vaultService.resolvePreferredUserVault(7L)).thenReturn(vault);
        when(vaultService.decryptKeyForChat(42L)).thenReturn("sk-plain");

        EngineCredentialsService.EngineCredentials creds = service.resolve(7L);

        assertThat(creds.available()).isTrue();
        assertThat(creds.apiKey()).isEqualTo("sk-plain");
        assertThat(creds.baseUrl()).isEqualTo("https://api.deepseek.com/v1");
        assertThat(creds.model()).isEqualTo("deepseek-v4-flash");
        verify(vaultService).decryptKeyForChat(42L);
    }

    @Test
    void baseUrlNormalizedToOpenAiSdkForm() {
        assertThat(EngineCredentialsService.toOpenAiSdkBaseUrl("https://api.deepseek.com"))
                .isEqualTo("https://api.deepseek.com/v1");
        assertThat(EngineCredentialsService.toOpenAiSdkBaseUrl("https://api.deepseek.com/"))
                .isEqualTo("https://api.deepseek.com/v1");
        assertThat(EngineCredentialsService.toOpenAiSdkBaseUrl("https://api.siliconflow.cn/v1"))
                .isEqualTo("https://api.siliconflow.cn/v1");
        assertThat(EngineCredentialsService.toOpenAiSdkBaseUrl("https://api.siliconflow.cn/v1/"))
                .isEqualTo("https://api.siliconflow.cn/v1");
        assertThat(EngineCredentialsService.toOpenAiSdkBaseUrl("http://127.0.0.1:11434"))
                .isEqualTo("http://127.0.0.1:11434/v1");
        assertThat(EngineCredentialsService.toOpenAiSdkBaseUrl("  ")).isEmpty();
        assertThat(EngineCredentialsService.toOpenAiSdkBaseUrl(null)).isEmpty();
    }
}
