package com.lianyu.admin.release;

import com.lianyu.admin.identity.AdminAuthorizationService;
import com.lianyu.common.base.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/v1/releases")
@RequiredArgsConstructor
public class ReleaseController {
    private final AdminAuthorizationService authorization;
    private final JdbcTemplate jdbc;
    @GetMapping public Result<List<Map<String,Object>>> list() { authorization.require("release.manage"); return Result.ok(jdbc.queryForList("SELECT id,version,channel,state,mandatory,package_size,sha512,published_at,created_at FROM app_release ORDER BY created_at DESC LIMIT 100")); }
    @PostMapping public Result<Void> create(@RequestBody Map<String,Object> body) { authorization.require("release.manage"); jdbc.update("INSERT INTO app_release(version,channel,notes,mandatory,created_by) VALUES(?,?,?,?,?)", body.get("version"), body.getOrDefault("channel","stable"), body.get("notes"), Boolean.TRUE.equals(body.get("mandatory")), authorization.currentAdminId()); return Result.ok(); }
}
