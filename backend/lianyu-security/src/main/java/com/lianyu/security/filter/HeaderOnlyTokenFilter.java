package com.lianyu.security.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 禁止通过 URL 查询参数传递登录令牌，避免令牌进入访问日志。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class HeaderOnlyTokenFilter extends OncePerRequestFilter {

    private static final String TOKEN_NAME = "lianyu-token";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getParameter(TOKEN_NAME) != null) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":400,\"message\":\"请通过请求头传递登录凭证\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}
