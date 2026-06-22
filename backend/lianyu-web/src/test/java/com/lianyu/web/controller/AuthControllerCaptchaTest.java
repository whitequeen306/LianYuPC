package com.lianyu.web.controller;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.lianyu.service.auth.AuthRateLimiter;
import com.lianyu.service.auth.AuthService;
import com.lianyu.service.auth.CaptchaService;
import com.lianyu.web.util.ClientIpResolver;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AuthControllerCaptchaTest {

    @Test
    void captchaResponse_doesNotExposeExpression() {
        AuthService authService = mock(AuthService.class);
        CaptchaService captchaService = mock(CaptchaService.class);
        AuthRateLimiter rateLimiter = mock(AuthRateLimiter.class);
        ClientIpResolver clientIpResolver = mock(ClientIpResolver.class);

        when(captchaService.generate()).thenReturn(
                new CaptchaService.CaptchaChallenge("id1", "1 + 2 = ?", "base64png"));

        AuthController controller = new AuthController(authService, captchaService, rateLimiter, clientIpResolver);
        Map<String, String> data = controller.captcha().getData();

        assertTrue(data.containsKey("captchaId"));
        assertTrue(data.containsKey("imageBase64"));
        assertFalse(data.containsKey("expression"));
    }
}
