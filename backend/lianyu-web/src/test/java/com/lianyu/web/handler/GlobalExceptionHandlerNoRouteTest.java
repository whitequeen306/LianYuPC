package com.lianyu.web.handler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.lianyu.common.base.ErrorCode;
import com.lianyu.common.base.Result;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 「路由不存在」必须是 404，不能是 500。
 *
 * <p>这条用例来自一次真实的线上投诉：存量客户端仍在轮询 2026-10-17 已下线的
 * `/api/community/push-catchup`，旧代码把它当未知异常兜底成
 * `500 + 服务暂时不可用`，于是用户每次重连都看到一个「后端挂了」的提示 ——
 * 而服务其实完全正常。
 */
class GlobalExceptionHandlerNoRouteTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void unknownRoute_returns404_notServerError() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(request.getHeader("Accept")).thenReturn("application/json");
        when(request.getRequestURI()).thenReturn("/api/community/push-catchup");
        when(request.getMethod()).thenReturn("POST");

        ResponseEntity<Result<Void>> entity = handler.handleNoResource(
                new NoResourceFoundException(HttpMethod.POST, "api/community/push-catchup"),
                request, response);

        assertEquals(404, entity.getStatusCode().value());
        assertEquals(ErrorCode.NOT_FOUND.getCode(), entity.getBody().getCode());
        assertNotEquals(ErrorCode.INTERNAL_ERROR.getCode(), entity.getBody().getCode(),
                "路由不存在不能报成 500「服务暂时不可用」");
    }

    @Test
    void unknownRoute_duringStream_doesNotWriteJsonBody() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(request.getHeader("Accept")).thenReturn("text/event-stream");
        when(request.getRequestURI()).thenReturn("/api/conversation/1/messages/stream");

        ResponseEntity<Result<Void>> entity = handler.handleNoResource(
                new NoResourceFoundException(HttpMethod.GET, "api/whatever"),
                request, response);

        assertEquals(null, entity, "SSE 已开始写响应时不能追加 JSON 错误体");
    }
}
