package com.lianyu.service.ai;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lianyu.dao.mapper.ApiKeyVaultMapper;
import com.lianyu.security.util.JasyptUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ApiKeyVaultServicePlatformChatTest {

    @Mock private ApiKeyVaultMapper vaultMapper;
    @Mock private JasyptUtil jasyptUtil;

    private ApiKeyVaultService service;

    @BeforeEach
    void setUp() {
        service = new ApiKeyVaultService(vaultMapper, jasyptUtil);
    }

    @Test
    void resolveForChat_platformOrBlank_returnsNull() {
        assertNull(service.resolveForChat(1L, "platform"));
        assertNull(service.resolveForChat(1L, null));
        assertNull(service.resolveForChat(1L, "  "));
    }

    @Test
    void isPlatformOrBlank_detectsReservedAlias() {
        assertTrue(ApiKeyVaultService.isPlatformOrBlank(null));
        assertTrue(ApiKeyVaultService.isPlatformOrBlank(""));
        assertTrue(ApiKeyVaultService.isPlatformOrBlank("platform"));
        assertTrue(ApiKeyVaultService.isPlatformOrBlank("PLATFORM"));
        assertFalse(ApiKeyVaultService.isPlatformOrBlank("my-deepseek"));
    }
}
