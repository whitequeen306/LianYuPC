package com.lianyu.admin.identity;

import cn.dev33.satoken.stp.StpUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AdminAuthorizationService {
    private final JdbcTemplate jdbc;

    public long currentAdminId() {
        String loginId = StpUtil.getLoginIdAsString();
        if (!loginId.startsWith("admin:")) throw new IllegalStateException("管理员登录已失效");
        return Long.parseLong(loginId.substring("admin:".length()));
    }

    public void require(String permission) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM admin_user_role ur JOIN admin_role r ON r.id=ur.role_id JOIN admin_role_permission rp ON rp.role_id=r.id JOIN admin_permission p ON p.id=rp.permission_id WHERE ur.admin_user_id=? AND p.permission_key=?", Long.class, currentAdminId(), permission);
        boolean superAdmin = jdbc.queryForObject("SELECT COUNT(*) FROM admin_user_role ur JOIN admin_role r ON r.id=ur.role_id WHERE ur.admin_user_id=? AND r.role_key='super_admin'", Long.class, currentAdminId()) > 0;
        if (!superAdmin && (count == null || count == 0)) throw new SecurityException("没有操作权限");
    }
}
