package com.lianyu.admin.operations;

import com.lianyu.admin.identity.AdminAuthorizationService;
import com.lianyu.admin.audit.AdminAuditService;
import com.lianyu.common.base.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/v1")
@RequiredArgsConstructor
public class AdminOperationsController {
    private final AdminAuthorizationService authorization;
    private final JdbcTemplate jdbc;
    private final AdminAuditService audit;

    @GetMapping("/users")
    public Result<List<Map<String, Object>>> users() {
        authorization.require("user.read");
        return Result.ok(jdbc.queryForList("SELECT id,username,nickname,COALESCE(JSON_UNQUOTE(JSON_EXTRACT(settings_json, '$.adminStatus')), 'active') AS admin_status,created_at,updated_at FROM `user` ORDER BY id DESC LIMIT 200"));
    }

    @PostMapping("/users/{id}/ban")
    public Result<Void> ban(@PathVariable long id) { authorization.require("user.moderate"); jdbc.update("UPDATE `user` SET settings_json=JSON_SET(COALESCE(settings_json,JSON_OBJECT()), '$.adminStatus', 'banned') WHERE id=?", id); audit.record("user.ban","user",String.valueOf(id),"success",null); return Result.ok(); }

    @PostMapping("/users/{id}/unban")
    public Result<Void> unban(@PathVariable long id) { authorization.require("user.moderate"); jdbc.update("UPDATE `user` SET settings_json=JSON_SET(COALESCE(settings_json,JSON_OBJECT()), '$.adminStatus', 'active') WHERE id=?", id); audit.record("user.unban","user",String.valueOf(id),"success",null); return Result.ok(); }

    @GetMapping("/announcements")
    public Result<List<Map<String, Object>>> announcements() {
        authorization.require("announcement.manage");
        return Result.ok(jdbc.queryForList("SELECT id,title,state,published_at,created_at,updated_at FROM announcement ORDER BY created_at DESC LIMIT 100"));
    }

    @GetMapping("/audit-logs")
    public Result<List<Map<String, Object>>> auditLogs() {
        authorization.require("audit.read");
        return Result.ok(jdbc.queryForList("SELECT id,actor_id,action_key,target_type,target_id,result,ip_address,trace_id,created_at FROM admin_audit_log ORDER BY id DESC LIMIT 200"));
    }
}
