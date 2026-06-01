package com.lianyu.web.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.lianyu.common.base.ErrorCode;
import com.lianyu.common.base.Result;
import com.lianyu.common.exception.BusinessException;
import com.lianyu.service.AuthRateLimiter;
import com.lianyu.service.AuthService;
import com.lianyu.service.CaptchaService;
import com.lianyu.service.dto.CaptchaVerifyRequest;
import com.lianyu.service.dto.LoginRequest;
import com.lianyu.service.dto.LoginResponse;
import com.lianyu.service.dto.RegisterRequest;
import com.lianyu.service.dto.UpdateProfileRequest;
import com.lianyu.service.dto.UserProfile;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/**
 * 认证相关接口。
 * <ul>
 *   <li>登录/注册前需先调用 {@code GET /api/auth/captcha} 获取验证码，答对才能继续。</li>
 *   <li>验证码有效期 2 分钟，用后即焚。</li>
 * </ul>
 */
@Tag(name = "Auth", description = "登录注册")
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final CaptchaService captchaService;
    private final AuthRateLimiter authRateLimiter;

    @Operation(summary = "获取验证码")
    @GetMapping("/captcha")
    public Result<Map<String, String>> captcha() {
        CaptchaService.CaptchaChallenge challenge = captchaService.generate();
        return Result.ok(Map.of(
                "captchaId", challenge.id(),
                "expression", challenge.expression()
        ));
    }

    @Operation(summary = "注册")
    @PostMapping("/register")
    public Result<Void> register(@Valid @RequestBody RegisterRequest request, HttpServletRequest httpRequest) {
        authRateLimiter.checkLoginOrRegister(resolveClientIp(httpRequest), request.getUsername());
        verifyCaptcha(request.getCaptcha());
        authService.register(request);
        return Result.ok();
    }

    @Operation(summary = "登录")
    @PostMapping("/login")
    public Result<LoginResponse> login(@Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        authRateLimiter.checkLoginOrRegister(resolveClientIp(httpRequest), request.getUsername());
        verifyCaptcha(request.getCaptcha());
        return Result.ok(authService.login(request));
    }

    @Operation(summary = "登出")
    @PostMapping("/logout")
    public Result<Void> logout() {
        authService.logout();
        return Result.ok();
    }

    @Operation(summary = "获取当前用户信息")
    @GetMapping("/me")
    public Result<UserProfile> me() {
        long userId = StpUtil.getLoginIdAsLong();
        return Result.ok(authService.me(userId));
    }

    @Operation(summary = "更新当前用户资料")
    @PutMapping("/me")
    public Result<UserProfile> updateProfile(@Valid @RequestBody UpdateProfileRequest request) {
        long userId = StpUtil.getLoginIdAsLong();
        return Result.ok(authService.updateProfile(userId, request));
    }

    @Operation(summary = "上传当前用户头像")
    @PostMapping("/me/avatar")
    public Result<UserProfile> uploadAvatar(@RequestParam("file") MultipartFile file) {
        long userId = StpUtil.getLoginIdAsLong();
        return Result.ok(authService.uploadAvatar(userId, file));
    }

    private void verifyCaptcha(CaptchaVerifyRequest captcha) {
        if (captcha == null
                || captcha.getCaptchaId() == null || captcha.getCaptchaId().isBlank()
                || captcha.getCaptchaAnswer() == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "请先完成验证码");
        }
        if (!captchaService.verify(captcha.getCaptchaId(), captcha.getCaptchaAnswer())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "验证码错误或已过期，请刷新重试");
        }
    }

    private static String resolveClientIp(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
        }
        return request.getRemoteAddr();
    }
}
