package com.lianyu.admin.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lianyu.admin.identity.AdminAuthorizationService;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AdminAuditService {
    private final JdbcTemplate jdbc;
    private final AdminAuthorizationService authorization;
    private final ObjectMapper objectMapper;

    public void record(String action, String targetType, String targetId, String result, Map<String, ?> detail) {
        Map<String, Object> safe = new LinkedHashMap<>();
        if (detail != null) detail.forEach((key, value) -> {
            String k = key.toLowerCase();
            safe.put(key, k.contains("password") || k.contains("token") || k.contains("secret") || k.contains("key") ? "[REDACTED]" : value);
        });
        String json;
        try { json = objectMapper.writeValueAsString(safe); } catch (JsonProcessingException e) { json = "{}"; }
        Long actor = null;
        try { actor = authorization.currentAdminId(); } catch (RuntimeException ignored) {}
        jdbc.update("INSERT INTO admin_audit_log(actor_id,action_key,target_type,target_id,result,detail_json,trace_id) VALUES(?,?,?,?,?,?,?)", actor, action, targetType, targetId, result, json, MDC.get("traceId"));
    }
}
