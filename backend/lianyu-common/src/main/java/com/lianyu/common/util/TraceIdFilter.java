package com.lianyu.common.util;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;
import java.io.IOException;
import java.util.UUID;

public class TraceIdFilter implements Filter {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest httpRequest = (HttpServletRequest) request;
        String traceId = httpRequest.getHeader("X-Trace-Id");
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString().replace("-", "");
        }

        try {
            MDC.put("traceId", traceId);
            chain.doFilter(request, response);
        } finally {
            MDC.clear();
        }
    }
}
