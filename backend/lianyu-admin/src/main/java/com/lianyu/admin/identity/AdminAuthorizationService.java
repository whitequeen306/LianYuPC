package com.lianyu.admin.identity;

import cn.dev33.satoken.stp.StpUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.util.HexFormat;

@Service
@RequiredArgsConstructor
public class AdminAuthorizationService {
    private final JdbcTemplate jdbc;

    public long currentAdminId() {
        String loginId = StpUtil.getLoginIdAsString();
        if (!loginId.startsWith("admin:")) throw new IllegalStateException("管理员登录已失效");
        long id = Long.parseLong(loginId.substring("admin:".length()));
        String token = StpUtil.getTokenValue();
        String hash;
        try { hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
        Long valid = jdbc.queryForObject("SELECT COUNT(*) FROM admin_session s JOIN admin_user u ON u.id=s.admin_user_id WHERE s.admin_user_id=? AND s.token_hash=? AND s.revoked_at IS NULL AND s.expires_at>CURRENT_TIMESTAMP(3) AND u.status='active'", Long.class, id, hash);
        if (valid == null || valid == 0) { StpUtil.logout(); throw new IllegalStateException("管理员会话已撤销或过期"); }
        return id;
    }

    public void require(String permission) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM admin_user_role ur JOIN admin_role r ON r.id=ur.role_id JOIN admin_role_permission rp ON rp.role_id=r.id JOIN admin_permission p ON p.id=rp.permission_id WHERE ur.admin_user_id=? AND p.permission_key=?", Long.class, currentAdminId(), permission);
        boolean superAdmin = jdbc.queryForObject("SELECT COUNT(*) FROM admin_user_role ur JOIN admin_role r ON r.id=ur.role_id WHERE ur.admin_user_id=? AND r.role_key='super_admin'", Long.class, currentAdminId()) > 0;
        if (!superAdmin && (count == null || count == 0)) throw new SecurityException("没有操作权限");
    }
}
