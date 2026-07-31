package com.lianyu.service.ai;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lianyu.dao.entity.ApiKeyVault;
import com.lianyu.dao.mapper.ApiKeyVaultMapper;
import com.lianyu.security.util.JasyptUtil;
import com.lianyu.service.dto.VaultEntryResponse;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@ExtendWith(MockitoExtension.class)
class ApiKeyVaultServicePlatformChatTest {

    @Mock private ApiKeyVaultMapper vaultMapper;
    @Mock private JasyptUtil jasyptUtil;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOperations;

    private ApiKeyVaultService service;

    @BeforeEach
    void setUp() {
        service = new ApiKeyVaultService(vaultMapper, jasyptUtil, redisTemplate);
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

    @Test
    void resolveForLogic_returnsDefaultPoolEntry() {
        ApiKeyVault vault = new ApiKeyVault();
        vault.setId(10L);
        vault.setVaultScope(ApiKeyVaultService.DEFAULT_SCOPE);
        vault.setProvider("platform");
        vault.setEnabled(1);
        vault.setBaseUrl("https://api.deepseek.com");
        vault.setModelDefault("deepseek-v4-flash");
        vault.setApiKeyEncrypted("enc");

        when(vaultMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(vault));
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(any())).thenReturn(1L);
        when(jasyptUtil.decrypt("enc")).thenReturn("sk-test-key-123456");

        VaultEntryResponse resolved = service.resolveForLogic(1L);
        assertTrue(resolved != null);
        assertTrue(resolved.getId().equals(10L));
        assertTrue("deepseek-v4-flash".equals(resolved.getModelDefault()));
    }
}
