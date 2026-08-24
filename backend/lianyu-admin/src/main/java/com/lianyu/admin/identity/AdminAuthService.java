package com.lianyu.admin.identity;

import cn.dev33.satoken.stp.SaTokenInfo;
import cn.dev33.satoken.stp.StpUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AdminAuthService {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;

    public AdminDtos.LoginResponse login(AdminDtos.LoginRequest request) {
        var rows = jdbc.queryForList("SELECT id, username, password_hash, status, totp_enabled, locked_until FROM admin_user WHERE username=?", request.username().trim());
        if (rows.isEmpty()) throw new IllegalArgumentException("用户名或密码错误");
        Map<String, Object> row = rows.get(0);
        if (!"active".equals(row.get("status"))) throw new IllegalStateException("管理员账号已停用");
        var lockedUntil = row.get("locked_until");
        if (lockedUntil != null && lockedUntil.toString().compareTo(Instant.now().toString()) > 0) throw new IllegalStateException("登录暂时锁定");
        if (!passwordEncoder.matches(request.password(), String.valueOf(row.get("password_hash")))) throw new IllegalArgumentException("用户名或密码错误");
        boolean otpRequired = Boolean.TRUE.equals(row.get("totp_enabled"));
        if (otpRequired && (request.otp() == null || request.otp().isBlank())) return new AdminDtos.LoginResponse(null, request.username(), true);
        long id = ((Number) row.get("id")).longValue();
        StpUtil.login("admin:" + id);
        SaTokenInfo token = StpUtil.getTokenInfo();
        jdbc.update("UPDATE admin_user SET failed_attempts=0,last_login_at=CURRENT_TIMESTAMP(3) WHERE id=?", id);
        return new AdminDtos.LoginResponse(token.getTokenValue(), String.valueOf(row.get("username")), false);
    }

    public void logout() { StpUtil.logout(); }
}
