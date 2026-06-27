package com.lianyu.web.handler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.dev33.satoken.exception.NotLoginException;
import com.lianyu.common.base.ErrorCode;
import com.lianyu.common.base.Result;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

class GlobalExceptionHandlerSseTest {

  private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

  @Test
  void notLogin_onSseStream_sets401WithoutJsonBody() {
    HttpServletRequest request = mock(HttpServletRequest.class);
    HttpServletResponse response = mock(HttpServletResponse.class);
    when(request.getHeader("Accept")).thenReturn("text/event-stream");
    when(request.getRequestURI()).thenReturn("/api/conversation/messages/stream");
    when(response.isCommitted()).thenReturn(false);

    ResponseEntity<?> entity =
        handler.handleNotLogin(NotLoginException.newInstance(null, null, null, null), request, response);

    assertNull(entity);
    verify(response).setStatus(401);
  }

  @Test
  void notLogin_onRegularApi_returns401Json() {
    HttpServletRequest request = mock(HttpServletRequest.class);
    HttpServletResponse response = mock(HttpServletResponse.class);
    when(request.getHeader("Accept")).thenReturn("application/json");
    when(request.getRequestURI()).thenReturn("/api/desktop/observe");

    ResponseEntity<Result<Void>> entity =
        handler.handleNotLogin(NotLoginException.newInstance(null, null, null, null), request, response);

    assertEquals(401, entity.getStatusCode().value());
    assertEquals(ErrorCode.UNAUTHORIZED.getCode(), entity.getBody().getCode());
  }
}
