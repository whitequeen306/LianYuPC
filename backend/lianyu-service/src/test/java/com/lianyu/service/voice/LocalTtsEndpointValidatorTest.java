package com.lianyu.service.voice;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lianyu.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

class LocalTtsEndpointValidatorTest {

    @Test
    void acceptsLoopbackAndPrivate() {
        assertDoesNotThrow(() -> LocalTtsEndpointValidator.normalizeAndValidate("http://127.0.0.1:9880"));
        assertDoesNotThrow(() -> LocalTtsEndpointValidator.normalizeAndValidate("http://localhost:9880"));
        assertDoesNotThrow(() -> LocalTtsEndpointValidator.normalizeAndValidate("http://192.168.1.10:9880"));
        assertDoesNotThrow(() -> LocalTtsEndpointValidator.normalizeAndValidate("http://10.0.0.5:9880/"));
    }

    @Test
    void rejectsPublicHosts() {
        assertThrows(BusinessException.class,
                () -> LocalTtsEndpointValidator.normalizeAndValidate("https://evil.example.com/tts"));
        assertThrows(BusinessException.class,
                () -> LocalTtsEndpointValidator.normalizeAndValidate("http://8.8.8.8:80"));
    }

    @Test
    void stripsTrailingSlash() {
        String n = LocalTtsEndpointValidator.normalizeAndValidate("http://127.0.0.1:9880/");
        assertTrue(n.endsWith("9880"));
    }
}
