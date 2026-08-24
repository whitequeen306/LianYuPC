package com.lianyu.admin.audit;

import com.lianyu.admin.identity.AdminAuthorizationService;
import com.lianyu.common.base.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/v1/audit")
@RequiredArgsConstructor
public class AdminAuditController {
    private final AdminAuthorizationService authorization;
    private final JdbcTemplate jdbc;
    @GetMapping public Result<List<Map<String,Object>>> list(@RequestParam(required=false) String action, @RequestParam(required=false) String result) {
        authorization.require("audit.read");
        return Result.ok(jdbc.queryForList("SELECT id,actor_id,action_key,target_type,target_id,result,detail_json,trace_id,created_at FROM admin_audit_log WHERE (? IS NULL OR action_key=?) AND (? IS NULL OR result=?) ORDER BY id DESC LIMIT 200", action, action, result, result));
    }
}
