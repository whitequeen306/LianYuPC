package com.lianyu.web.controller;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import cn.dev33.satoken.exception.SaTokenContextException;
import com.lianyu.service.voice.VoiceSynthesisService;
import com.lianyu.service.auth.AuthRateLimiter;
import com.lianyu.service.dto.ObserveDesktopRequest;
import com.lianyu.web.agent.DesktopObserveOrchestrator;
import com.lianyu.web.util.ClientIpResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

class ObserveControllerSecurityTest {

  @Test
  void observe_withoutLogin_throwsNotLogin() {
    ObserveController controller =
        new ObserveController(
            mock(VoiceSynthesisService.class),
            mock(AuthRateLimiter.class),
            mock(ClientIpResolver.class),
            mock(DesktopObserveOrchestrator.class));

    ObserveDesktopRequest request = new ObserveDesktopRequest();
    request.setImageBase64("dGVzdA==");
    HttpServletRequest httpRequest = mock(HttpServletRequest.class);

    assertThrows(SaTokenContextException.class, () -> controller.observe(request, httpRequest));
  }
}
