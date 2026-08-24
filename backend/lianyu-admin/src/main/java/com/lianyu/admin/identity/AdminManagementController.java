package com.lianyu.admin.identity;

import com.lianyu.admin.audit.AdminAuditService;
import com.lianyu.common.base.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/v1/admins")
@RequiredArgsConstructor
public class AdminManagementController {
    private final AdminAuthorizationService authorization;
    private final AdminAuditService audit;
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;

    @GetMapping public Result<List<Map<String,Object>>> list() { authorization.require("admin.manage"); return Result.ok(jdbc.queryForList("SELECT id,username,display_name,status,totp_enabled,last_login_at,created_at FROM admin_user ORDER BY id DESC")); }
    @GetMapping("/roles") public Result<List<Map<String,Object>>> roles() { authorization.require("admin.manage"); return Result.ok(jdbc.queryForList("SELECT id,role_key,role_name,protected_role FROM admin_role ORDER BY id")); }
    @PostMapping public Result<Void> create(@RequestBody AdminDtos.AdminCreateRequest request) {
        authorization.require("admin.manage");
        jdbc.update("INSERT INTO admin_user(username,display_name,password_hash) VALUES(?,?,?)", request.username().trim(), request.displayName().trim(), passwordEncoder.encode(request.password()));
        Long id = jdbc.queryForObject("SELECT id FROM admin_user WHERE username=?", Long.class, request.username().trim());
        String role = request.roleKey() == null || request.roleKey().isBlank() ? "operations" : request.roleKey();
        Long roleId = jdbc.queryForObject("SELECT id FROM admin_role WHERE role_key=?", Long.class, role);
        jdbc.update("INSERT INTO admin_user_role(admin_user_id,role_id) VALUES(?,?)", id, roleId);
        audit.record("admin.create", "admin_user", String.valueOf(id), "success", Map.of("username", request.username(), "password", request.password()));
        return Result.ok();
    }
    @PostMapping("/{id}/disable") public Result<Void> disable(@PathVariable long id) { authorization.require("admin.manage"); guardProtected(id); jdbc.update("UPDATE admin_user SET status='disabled' WHERE id=?", id); audit.record("admin.disable", "admin_user", String.valueOf(id), "success", null); return Result.ok(); }
    @PostMapping("/{id}/roles") public Result<Void> assignRole(@PathVariable long id, @RequestBody AdminDtos.RoleAssignmentRequest request) { authorization.require("admin.manage"); Long roleId=jdbc.queryForObject("SELECT id FROM admin_role WHERE role_key=?", Long.class, request.roleKey()); jdbc.update("DELETE FROM admin_user_role WHERE admin_user_id=?", id); jdbc.update("INSERT INTO admin_user_role(admin_user_id,role_id) VALUES(?,?)", id, roleId); audit.record("admin.role.assign", "admin_user", String.valueOf(id), "success", Map.of("role", request.roleKey())); return Result.ok(); }
    private void guardProtected(long id) { Long protectedCount=jdbc.queryForObject("SELECT COUNT(*) FROM admin_user_role ur JOIN admin_role r ON r.id=ur.role_id WHERE ur.admin_user_id=? AND r.protected_role=TRUE", Long.class, id); if (protectedCount != null && protectedCount > 0) { Long active=jdbc.queryForObject("SELECT COUNT(*) FROM admin_user u JOIN admin_user_role ur ON ur.admin_user_id=u.id JOIN admin_role r ON r.id=ur.role_id WHERE r.protected_role=TRUE AND u.status='active'", Long.class); if (active != null && active <= 1) throw new IllegalStateException("不能停用最后一个超级管理员"); } }
}
