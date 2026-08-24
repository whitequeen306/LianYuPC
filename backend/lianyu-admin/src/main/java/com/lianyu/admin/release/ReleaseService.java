package com.lianyu.admin.release;

import com.lianyu.admin.audit.AdminAuditService;
import com.lianyu.admin.identity.AdminAuthorizationService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
@RequiredArgsConstructor
public class ReleaseService {
    private final JdbcTemplate jdbc;
    private final ReleaseValidationService validation;
    private final AdminAuthorizationService authorization;
    private final AdminAuditService audit;

    public long create(String version, String channel, String notes, boolean mandatory) {
        authorization.require("release.manage");
        String normalizedVersion = validation.normalizeVersion(version);
        String normalizedChannel = validation.validateChannel(channel);
        Long duplicate = jdbc.queryForObject("SELECT COUNT(*) FROM app_release WHERE version=? AND channel=?", Long.class, normalizedVersion, normalizedChannel);
        if (duplicate != null && duplicate > 0) throw new IllegalStateException("该版本和渠道已存在");
        jdbc.update("INSERT INTO app_release(version,channel,notes,mandatory,state,created_by) VALUES(?,?,?,?, 'draft',?)", normalizedVersion, normalizedChannel, notes, mandatory, authorization.currentAdminId());
        Long id = jdbc.queryForObject("SELECT id FROM app_release WHERE version=? AND channel=?", Long.class, normalizedVersion, normalizedChannel);
        audit.record("release.create", "app_release", String.valueOf(id), "success", Map.of("version", normalizedVersion, "channel", normalizedChannel));
        return id;
    }

    public void transition(long id, String nextState) {
        authorization.require("release.manage");
        String current = jdbc.queryForObject("SELECT state FROM app_release WHERE id=?", String.class, id);
        if (!allowed(current, nextState)) throw new IllegalStateException("非法版本状态流转: " + current + " -> " + nextState);
        jdbc.update("UPDATE app_release SET state=?,published_at=CASE WHEN ?='published' THEN CURRENT_TIMESTAMP(3) ELSE published_at END WHERE id=?", nextState, nextState, id);
        audit.record("release.transition", "app_release", String.valueOf(id), "success", Map.of("from", current, "to", nextState));
    }
    private boolean allowed(String from, String to) {
        return switch (from) {
            case "draft" -> to.equals("uploading") || to.equals("archived");
            case "uploading" -> to.equals("validating") || to.equals("draft");
            case "validating" -> to.equals("ready") || to.equals("draft");
            case "ready" -> to.equals("published") || to.equals("archived");
            case "published" -> to.equals("rollout") || to.equals("rolled-back") || to.equals("archived");
            case "rollout" -> to.equals("published") || to.equals("stopped") || to.equals("rolled-back");
            case "stopped" -> to.equals("rollout") || to.equals("rolled-back");
            default -> false;
        };
    }
}
