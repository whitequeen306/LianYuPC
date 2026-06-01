package com.lianyu.web.filter;

import cn.dev33.satoken.stp.StpUtil;
import com.lianyu.common.i18n.OutputLanguage;
import com.lianyu.service.OutputLanguageService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
@RequiredArgsConstructor
public class OutputLanguageFilter extends OncePerRequestFilter {

    public static final String HEADER_OUTPUT_LANGUAGE = "X-LianYu-Output-Language";

    private final OutputLanguageService outputLanguageService;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String header = request.getHeader(HEADER_OUTPUT_LANGUAGE);
        if (header != null && !header.isBlank()) {
            String normalized = OutputLanguage.fromCode(header).getCode();
            try {
                if (StpUtil.isLogin()) {
                    outputLanguageService.cacheForUser(StpUtil.getLoginIdAsLong(), normalized);
                }
            } catch (Exception ignored) {
                // ignore when token missing on public endpoints
            }
        }
        filterChain.doFilter(request, response);
    }
}
