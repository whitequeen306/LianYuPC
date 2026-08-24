package com.lianyu.admin.release;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ReleaseResolverService {
    private final JdbcTemplate jdbc;
    public Map<String,Object> resolve(String channel, String subject) {
        String normalized = channel == null || channel.isBlank() ? "stable" : channel.toLowerCase();
        var rows = jdbc.queryForList("SELECT id,version,package_url,sha512,package_size,mandatory,notes FROM app_release WHERE channel=? AND state IN ('published','rollout') ORDER BY published_at DESC,id DESC LIMIT 20", normalized);
        for (Map<String,Object> row : rows) {
            if (inRollout(((Number) row.get("id")).longValue(), subject)) return Map.of("version", row.get("version"), "url", row.get("package_url"), "sha512", row.get("sha512"), "size", row.get("package_size"), "mandatory", row.get("mandatory"), "notes", row.get("notes"));
        }
        return Map.of("channel", normalized, "available", false);
    }
    private boolean inRollout(long releaseId, String subject) {
        List<Map<String,Object>> rollout = jdbc.queryForList("SELECT percentage,salt FROM release_rollout WHERE release_id=? AND state='active' ORDER BY id DESC LIMIT 1", releaseId);
        if (rollout.isEmpty()) return true;
        String salt = String.valueOf(rollout.get(0).get("salt")); double percentage = ((Number) rollout.get(0).get("percentage")).doubleValue();
        try { byte[] digest=MessageDigest.getInstance("SHA-256").digest((salt+":"+(subject==null?"anonymous":subject)).getBytes(StandardCharsets.UTF_8)); long value=(digest[0] & 0xffL) * 100L / 256L; return value < percentage; } catch(Exception e){ return false; }
    }
}
