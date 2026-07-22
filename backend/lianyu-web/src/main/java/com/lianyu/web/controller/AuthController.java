package com.lianyu.web.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.lianyu.common.base.ErrorCode;
import com.lianyu.common.base.Result;
import com.lianyu.common.exception.BusinessException;
import com.lianyu.service.auth.AuthRateLimiter;
import com.lianyu.service.auth.AuthService;
import com.lianyu.service.auth.CaptchaService;
import com.lianyu.service.dto.CaptchaVerifyRequest;
import com.lianyu.service.dto.ChangePasswordRequest;
import com.lianyu.service.dto.LoginRequest;
import com.lianyu.service.dto.LoginResponse;
import com.lianyu.service.dto.RegisterRequest;
import com.lianyu.service.dto.UpdateProfileRequest;
import com.lianyu.service.dto.UpdateUserSettingsRequest;
import com.lianyu.service.dto.UserProfile;
import com.lianyu.service.dto.UserSettingsResponse;
import com.lianyu.service.user.UserPublicProfileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Duration;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import com.lianyu.web.util.ClientIpResolver;

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
    private final ClientIpResolver clientIpResolver;
    private final UserPublicProfileService userPublicProfileService;

    @Operation(summary = "获取验证码")
    @GetMapping("/captcha")
    public Result<Map<String, String>> captcha() {
        CaptchaService.CaptchaChallenge challenge = captchaService.generate();
        return Result.ok(Map.of(
                "captchaId", challenge.id(),
                "imageBase64", challenge.imageBase64()
        ));
    }

    @Operation(summary = "注册")
    @PostMapping("/register")
    public Result<LoginResponse> register(@Valid @RequestBody RegisterRequest request, HttpServletRequest httpRequest) {
        String clientIp = clientIpResolver.resolve(httpRequest);
        authRateLimiter.checkLoginOrRegister(clientIp, request.getUsername());
        if (clientIp != null && !clientIp.isBlank()) {
            authRateLimiter.checkRateLimit("rate:register:ip:", clientIp.trim(),
                    3, Duration.ofDays(1), "该 IP 今日注册次数已达上限");
        }
        verifyCaptcha(request.getCaptcha());
        return Result.ok(authService.register(request));
    }

    @Operation(summary = "登录")
    @PostMapping("/login")
    public Result<LoginResponse> login(@Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        authRateLimiter.checkLoginOrRegister(clientIpResolver.resolve(httpRequest), request.getUsername());
        verifyCaptcha(request.getCaptcha());
        return Result.ok(authService.login(request));
    }

    @Operation(summary = "登出")
    @PostMapping("/logout")
    public Result<Void> logout() {
        authService.logout();
        return Result.ok();
    }

    @Operation(summary = "刷新会话（滑动续签）")
    @PostMapping("/refresh")
    public Result<LoginResponse> refresh() {
        long userId = StpUtil.getLoginIdAsLong();
        return Result.ok(authService.refresh(userId));
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
        authRateLimiter.checkRateLimit("rate:upload:avatar:", String.valueOf(userId),
                20, Duration.ofDays(1), "今日头像上传次数已达上限");
        return Result.ok(authService.uploadAvatar(userId, file));
    }

    @Operation(summary = "修改当前用户密码")
    @PutMapping("/me/password")
    public Result<Void> changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        long userId = StpUtil.getLoginIdAsLong();
        authService.changePassword(userId, request);
        return Result.ok();
    }

    @Operation(summary = "获取当前用户隐私设置")
    @GetMapping("/me/settings")
    public Result<UserSettingsResponse> getSettings() {
        long userId = StpUtil.getLoginIdAsLong();
        return Result.ok(userPublicProfileService.getMySettings(userId));
    }

    @Operation(summary = "更新当前用户隐私设置")
    @PutMapping("/me/settings")
    public Result<UserSettingsResponse> updateSettings(@RequestBody UpdateUserSettingsRequest request) {
        long userId = StpUtil.getLoginIdAsLong();
        return Result.ok(userPublicProfileService.updateMySettings(userId, request));
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

}
