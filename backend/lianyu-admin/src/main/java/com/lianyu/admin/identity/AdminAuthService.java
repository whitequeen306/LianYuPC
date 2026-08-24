package com.lianyu.admin.identity;

import cn.dev33.satoken.stp.SaTokenInfo;
import cn.dev33.satoken.stp.StpUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import java.util.Map;
import com.lianyu.security.util.JasyptUtil;

@Service
@RequiredArgsConstructor
public class AdminAuthService {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    private final JasyptUtil jasypt;

    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    public AdminDtos.LoginResponse login(AdminDtos.LoginRequest request) {
        var rows = jdbc.queryForList("SELECT id, username, password_hash, status, totp_enabled, totp_secret, locked_until FROM admin_user WHERE username=?", request.username().trim());
        if (rows.isEmpty()) throw new IllegalArgumentException("用户名或密码错误");
        Map<String, Object> row = rows.get(0);
        if (!"active".equals(row.get("status"))) throw new IllegalStateException("管理员账号已停用");
        var lockedUntil = row.get("locked_until");
        if (lockedUntil instanceof java.sql.Timestamp t && t.toInstant().isAfter(Instant.now())) throw new IllegalStateException("登录暂时锁定");
        if (!passwordEncoder.matches(request.password(), String.valueOf(row.get("password_hash")))) {
            jdbc.update("UPDATE admin_user SET failed_attempts=failed_attempts+1, locked_until=CASE WHEN failed_attempts+1>=5 THEN DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL 15 MINUTE) ELSE locked_until END WHERE id=?", row.get("id"));
            throw new IllegalArgumentException("用户名或密码错误");
        }
        boolean otpRequired = row.get("totp_enabled") instanceof Boolean b ? b : ((Number) row.getOrDefault("totp_enabled", 0)).intValue() != 0;
        if (otpRequired && (request.otp() == null || request.otp().isBlank())) return new AdminDtos.LoginResponse(null, request.username(), true, org.slf4j.MDC.get("traceId"));
        if (otpRequired && !TotpUtil.verify(jasypt.decrypt(String.valueOf(row.get("totp_secret"))), request.otp(), System.currentTimeMillis())) throw new IllegalArgumentException("用户名或密码错误");
        long id = ((Number) row.get("id")).longValue();
        StpUtil.login("admin:" + id);
        SaTokenInfo token = StpUtil.getTokenInfo();
        String tokenValue = token.getTokenValue();
        jdbc.update("INSERT INTO admin_session(admin_user_id,token_hash,expires_at) VALUES(?,?,DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL 8 HOUR))", id, hash(tokenValue));
        jdbc.update("UPDATE admin_user SET failed_attempts=0,last_login_at=CURRENT_TIMESTAMP(3) WHERE id=?", id);
        return new AdminDtos.LoginResponse(tokenValue, String.valueOf(row.get("username")), false, org.slf4j.MDC.get("traceId"));
    }

    public void logout() { StpUtil.logout(); }
    public String currentUsername() { long id = Long.parseLong(StpUtil.getLoginIdAsString().substring("admin:".length())); return jdbc.queryForObject("SELECT username FROM admin_user WHERE id=?", String.class, id); }
}
