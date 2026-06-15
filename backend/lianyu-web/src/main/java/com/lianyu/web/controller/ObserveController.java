package com.lianyu.web.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.lianyu.common.base.Result;
import com.lianyu.service.ai.AiChatService;
import com.lianyu.service.auth.AuthRateLimiter;
import com.lianyu.service.dto.ObserveDesktopRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/desktop")
@Tag(name = "桌面感知", description = "桌面截图分析 + 桌宠主动问候")
public class ObserveController {

    private static final int OBSERVE_PER_USER_PER_HOUR = 10;
    private static final int OBSERVE_PER_IP_PER_HOUR = 30;

    private final AiChatService aiChatService;
    private final AuthRateLimiter authRateLimiter;

    public ObserveController(AiChatService aiChatService, AuthRateLimiter authRateLimiter) {
        this.aiChatService = aiChatService;
        this.authRateLimiter = authRateLimiter;
    }

    @PostMapping("/observe")
    @Operation(summary = "桌面感知观察", description = "传入桌面截图、窗口信息与角色人设，返回角色语气的主动问候")
    public Result<Map<String, String>> observe(@Valid @RequestBody ObserveDesktopRequest request,
                                               HttpServletRequest httpRequest) {
        StpUtil.checkLogin();
        long userId = StpUtil.getLoginIdAsLong();
        authRateLimiter.checkRateLimit("rate:observe:user:", String.valueOf(userId),
                OBSERVE_PER_USER_PER_HOUR, Duration.ofHours(1), "屏幕观察过于频繁，请稍后再试");
        String clientIp = resolveClientIp(httpRequest);
        if (clientIp != null && !clientIp.isBlank()) {
            authRateLimiter.checkRateLimit("rate:observe:ip:", clientIp.trim(),
                    OBSERVE_PER_IP_PER_HOUR, Duration.ofHours(1), "请求过于频繁，请稍后再试");
        }

        try {
            String greeting = aiChatService.observeDesktop(
                    request.getImageBase64(), request.getWindowTitle(), request.getPersona());
            if (greeting == null || greeting.isBlank()) {
                return Result.fail(400, "未能生成问候语");
            }
            return Result.ok(Map.of("greeting", greeting));
        } catch (com.lianyu.common.exception.BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("Desktop observe failed", e);
            return Result.fail(500, "桌面感知服务繁忙，请稍后再试");
        }
    }

    private static String resolveClientIp(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return (comma >= 0 ? forwarded.substring(0, comma) : forwarded).trim();
        }
        return request.getRemoteAddr();
    }
}
