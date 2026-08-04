package com.lianyu.service.voice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lianyu.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

class DashScopeCloudEndpointValidatorTest {

    @Test
    void acceptsDefaultAndIntl() {
        assertEquals("https://dashscope.aliyuncs.com",
                DashScopeCloudEndpointValidator.normalizeBaseUrl(null));
        assertEquals("https://dashscope-intl.aliyuncs.com",
                DashScopeCloudEndpointValidator.normalizeBaseUrl("https://dashscope-intl.aliyuncs.com/"));
    }

    @Test
    void rejectsNonDashScope() {
        assertThrows(BusinessException.class,
                () -> DashScopeCloudEndpointValidator.normalizeBaseUrl("https://evil.example.com"));
        assertThrows(BusinessException.class,
                () -> DashScopeCloudEndpointValidator.normalizeBaseUrl("http://dashscope.aliyuncs.com"));
    }

    @Test
    void derivesPaths() {
        String base = "https://dashscope.aliyuncs.com";
        assertTrue(DashScopeCloudEndpointValidator.enrollUrl(base).endsWith("/customization"));
        assertTrue(DashScopeCloudEndpointValidator.synthUrl(base).contains("/multimodal-generation/"));
        assertEquals("wss://dashscope.aliyuncs.com/api-ws/v1/realtime",
                DashScopeCloudEndpointValidator.realtimeWsUrl(base));
    }
}
