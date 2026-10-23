package com.lianyu.web.agent;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 内网调用签名（Python backend-agent → Java）。
 *
 * <p>为什么需要：桌面能力层让 Python 反过来调用 Java（下发动作到客户端）。
 * 这条路径**没有 Sa-Token**（不是用户请求，`/internal/**` 也不经网关暴露），
 * 因此必须自带认证：共享密钥 + 时间窗 + 重放缓存，三者缺一不可。
 *
 * <p>**fail-closed**：未配置密钥时一律拒绝。宁可电脑操控用不了，
 * 也不能让一个无鉴权的「往用户电脑注入输入」的端点活着。
 */
@Slf4j
@Component
public class AgentInternalSigner {

    /** 共享密钥；为空 = 未启用（一律拒绝） */
    @Value("${lianyu.agent.internal-token:}")
    private String token;

    /** 允许的时钟偏差（秒）：超窗即拒，防重放 */
    @Value("${lianyu.agent.internal-window-seconds:30}")
    private long windowSeconds;

    /** 已用过的签名（sig → 首次见到的时间戳秒）。有界清理，避免内存无上限 */
    private final Map<String, Long> seenSignatures = new ConcurrentHashMap<>();
    private static final int MAX_SEEN = 2048;

    public boolean verify(String timestampHeader, String signatureHeader, String rawBody) {
        if (token == null || token.isBlank()) {
            log.warn("internal call rejected: lianyu.agent.internal-token not configured");
            return false;
        }
        if (timestampHeader == null || signatureHeader == null || rawBody == null) {
            return false;
        }
        long ts;
        try {
            ts = Long.parseLong(timestampHeader.trim());
        } catch (NumberFormatException e) {
            return false;
        }
        long now = System.currentTimeMillis() / 1000L;
        if (Math.abs(now - ts) > Math.max(1L, windowSeconds)) {
            log.warn("internal call rejected: timestamp skew {}s", now - ts);
            return false;
        }
        String expected = sign(token, timestampHeader.trim(), rawBody);
        if (!constantTimeEquals(expected, signatureHeader.trim())) {
            log.warn("internal call rejected: bad signature");
            return false;
        }
        // 重放：同一签名在一次时间窗内只允许出现一次
        prune(now);
        Long previous = seenSignatures.putIfAbsent(signatureHeader.trim(), now);
        if (previous != null) {
            log.warn("internal call rejected: replayed signature");
            return false;
        }
        return true;
    }

    private void prune(long now) {
        if (seenSignatures.size() < MAX_SEEN) {
            return;
        }
        long cutoff = now - Math.max(1L, windowSeconds) * 2;
        seenSignatures.entrySet().removeIf(e -> e.getValue() < cutoff);
    }

    /** 与 Python `desktop_channel.sign_body` 逐字节一致：`{ts}.{body}` 的 HMAC-SHA256，十六进制。 */
    public static String sign(String secret, String timestamp, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update(timestamp.getBytes(StandardCharsets.UTF_8));
            mac.update((byte) '.');
            mac.update(body.getBytes(StandardCharsets.UTF_8));
            return "sha256=" + HexFormat.of().formatHex(mac.doFinal());
        } catch (Exception e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
