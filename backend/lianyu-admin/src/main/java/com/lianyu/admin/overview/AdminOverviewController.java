package com.lianyu.admin.overview;

import com.lianyu.admin.identity.AdminAuthorizationService;
import com.lianyu.common.base.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/admin/v1/overview")
@RequiredArgsConstructor
public class AdminOverviewController {
    private final AdminAuthorizationService authorization;
    private final JdbcTemplate jdbc;
    @GetMapping
    public Result<Map<String, Object>> overview() {
        authorization.require("system.health");
        Integer admins = jdbc.queryForObject("SELECT COUNT(*) FROM admin_user WHERE status='active'", Integer.class);
        Integer releases = jdbc.queryForObject("SELECT COUNT(*) FROM app_release WHERE state='published'", Integer.class);
        return Result.ok(Map.of("status", "online", "activeAdmins", admins == null ? 0 : admins, "publishedReleases", releases == null ? 0 : releases));
    }
}
