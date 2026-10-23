package com.lianyu.service.tools.bridge;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 桌面能力审计：每次 `desk_*` 尝试落一行（成功/失败/被拒/超时都记）。
 *
 * <p>为什么写在 Java 而不是 Python：Python 对 MySQL 只读是既有红线（见 `config.py`），
 * 审计属于写路径；而且 Java 在 `exec` 这一步正好同时持有 danger/evidence/耗时/结果。
 *
 * <p>为什么存摘要而不是参数明文：参数里可能有路径、窗口标题、剪贴板内容。
 * 存 `sha256(args)[0:16]` 足够和客户端本地环形缓冲对上号，又不把隐私写进库。
 *
 * <p>**审计失败绝不阻断执行**：写库异常只记 WARN。宁可能力照常可用 + 少一条流水，
 * 也不能因为审计表没建好就让用户点不动电脑。
 */
@Slf4j
@Service
public class CapabilityAuditService {

    private final JdbcTemplate jdbc;

    @Value("${lianyu.tools.agent-bridge.audit-enabled:true}")
    private boolean enabled;

    public CapabilityAuditService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 一次能力尝试的审计记录。 */
    public record AuditEntry(Long userId, String sessionId, String stepId, String tool,
                             String argsDigest, String danger, String evidence,
                             boolean confirmed, boolean ok, String errorCode, long elapsedMs) {
    }

    private static final String INSERT_SQL = """
            INSERT INTO agent_capability_audit
                (user_id, session_id, step_id, tool, args_digest, danger, evidence,
                 confirmed, ok, error_code, elapsed_ms)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    public void record(AuditEntry entry) {
        if (!enabled || entry == null || entry.userId() == null) {
            return;
        }
        try {
            jdbc.update(INSERT_SQL,
                    entry.userId(),
                    cut(entry.sessionId(), 64),
                    cut(entry.stepId(), 96),
                    cut(entry.tool(), 64),
                    cut(entry.argsDigest(), 16),
                    cut(entry.danger(), 2),
                    cut(entry.evidence(), 16),
                    entry.confirmed() ? 1 : 0,
                    entry.ok() ? 1 : 0,
                    cut(entry.errorCode(), 64),
                    (int) Math.min(Integer.MAX_VALUE, Math.max(0, entry.elapsedMs())));
        } catch (Exception e) {
            log.warn("capability audit write failed (tool={}, step={}): {}",
                    entry.tool(), entry.stepId(), e.toString());
        }
    }

    /** 参数摘要：不存明文，只留 16 位十六进制前缀。 */
    public static String digestArgs(String argsJson) {
        if (argsJson == null || argsJson.isBlank()) {
            return "";
        }
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] hash = sha.digest(argsJson.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 16);
        } catch (Exception e) {
            return "";
        }
    }

    /** 从客户端回传的 policy 里取确认结果（客户端没有回传时按未确认记）。 */
    public static boolean confirmedFrom(Map<String, Object> policy) {
        if (policy == null) {
            return false;
        }
        Object value = policy.get("confirmed");
        return Boolean.TRUE.equals(value) || "true".equalsIgnoreCase(String.valueOf(value));
    }

    private static String cut(String value, int max) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }
}
