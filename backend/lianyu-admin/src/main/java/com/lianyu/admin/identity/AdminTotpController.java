package com.lianyu.admin.identity;

import com.lianyu.common.base.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import com.lianyu.security.util.JasyptUtil;

@RestController
@RequestMapping("/api/admin/v1/security/totp")
@RequiredArgsConstructor
public class AdminTotpController {
    private final AdminAuthorizationService authorization;
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    private final JasyptUtil jasypt;

    @PostMapping("/setup")
    public Result<AdminDtos.TotpSetupResponse> setup() {
        long id = authorization.currentAdminId();
        String secret = TotpUtil.newSecret();
        jdbc.update("UPDATE admin_user SET totp_secret=?, totp_enabled=FALSE WHERE id=?", jasypt.encrypt(secret), id);
        String username = jdbc.queryForObject("SELECT username FROM admin_user WHERE id=?", String.class, id);
        return Result.ok(new AdminDtos.TotpSetupResponse(secret, TotpUtil.uri("LianYu Admin", username, secret)));
    }

    @PostMapping("/confirm")
    public Result<Void> confirm(@RequestBody AdminDtos.TotpCodeRequest request) {
        long id = authorization.currentAdminId();
        String secret = jdbc.queryForObject("SELECT totp_secret FROM admin_user WHERE id=?", String.class, id);
        if (!TotpUtil.verify(jasypt.decrypt(secret), request.code(), System.currentTimeMillis())) throw new IllegalArgumentException("验证码无效");
        jdbc.update("UPDATE admin_user SET totp_enabled=TRUE WHERE id=?", id);
        return Result.ok();
    }

    @PostMapping("/disable")
    public Result<Void> disable(@RequestBody AdminDtos.TotpCodeRequest request) {
        long id = authorization.currentAdminId();
        var row = jdbc.queryForMap("SELECT password_hash,totp_secret FROM admin_user WHERE id=?", id);
        if (!passwordEncoder.matches(request.code(), String.valueOf(row.get("password_hash"))) && !TotpUtil.verify(jasypt.decrypt(String.valueOf(row.get("totp_secret"))), request.code(), System.currentTimeMillis())) throw new IllegalArgumentException("验证失败");
        jdbc.update("UPDATE admin_user SET totp_enabled=FALSE,totp_secret=NULL WHERE id=?", id);
        return Result.ok();
    }
}
