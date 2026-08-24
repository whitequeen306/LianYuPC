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
    private final ReleaseService releaseService;
    @GetMapping public Result<List<Map<String,Object>>> list() { authorization.require("release.manage"); return Result.ok(jdbc.queryForList("SELECT id,version,channel,state,mandatory,package_size,sha512,published_at,created_at FROM app_release ORDER BY created_at DESC LIMIT 100")); }
    @PostMapping public Result<Map<String,Object>> create(@RequestBody Map<String,Object> body) { long id = releaseService.create(String.valueOf(body.get("version")), String.valueOf(body.getOrDefault("channel","stable")), body.get("notes") == null ? null : String.valueOf(body.get("notes")), Boolean.TRUE.equals(body.get("mandatory"))); return Result.ok(Map.of("id", id)); }
    @PostMapping("/{id}/state/{state}") public Result<Void> transition(@PathVariable long id, @PathVariable String state) { releaseService.transition(id, state); return Result.ok(); }
}
