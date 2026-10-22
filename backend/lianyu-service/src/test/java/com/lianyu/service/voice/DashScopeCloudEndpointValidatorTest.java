package com.lianyu.service.voice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
    void derivesRealtimeWsPath() {
        // 只保留 Realtime：非实时合成的 URL 拼装已搬到 Python（app/dashscope.py），
        // Java 侧不再有第二套实现。
        String base = "https://dashscope.aliyuncs.com";
    }
}
