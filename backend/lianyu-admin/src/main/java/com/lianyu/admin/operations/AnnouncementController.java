package com.lianyu.admin.operations;

import com.lianyu.admin.audit.AdminAuditService;
import com.lianyu.admin.identity.AdminAuthorizationService;
import com.lianyu.common.base.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/admin/v1/announcements")
@RequiredArgsConstructor
public class AnnouncementController {
    private final AdminAuthorizationService authorization;
    private final AdminAuditService audit;
    private final JdbcTemplate jdbc;
    @PostMapping public Result<Map<String,Object>> create(@RequestBody Map<String,Object> body) {
        authorization.require("announcement.manage");
        Object title=body.get("title"), content=body.get("body");
        if(title==null || String.valueOf(title).isBlank() || content==null || String.valueOf(content).isBlank()) throw new IllegalArgumentException("公告标题和正文不能为空");
        jdbc.update("INSERT INTO announcement(title,body,created_by) VALUES(?,?,?)", title, content, authorization.currentAdminId());
        Long id=jdbc.queryForObject("SELECT id FROM announcement WHERE created_by=? ORDER BY id DESC LIMIT 1", Long.class, authorization.currentAdminId());
        audit.record("announcement.create","announcement",String.valueOf(id),"success",Map.of("title",title));
        return Result.ok(Map.of("id",id));
    }
    @PostMapping("/{id}/publish") public Result<Void> publish(@PathVariable long id) { authorization.require("announcement.manage"); jdbc.update("UPDATE announcement SET state='published',published_at=CURRENT_TIMESTAMP(3) WHERE id=? AND state='draft'", id); audit.record("announcement.publish","announcement",String.valueOf(id),"success",null); return Result.ok(); }
    @PostMapping("/{id}/withdraw") public Result<Void> withdraw(@PathVariable long id) { authorization.require("announcement.manage"); jdbc.update("UPDATE announcement SET state='withdrawn' WHERE id=? AND state='published'", id); audit.record("announcement.withdraw","announcement",String.valueOf(id),"success",null); return Result.ok(); }
}
