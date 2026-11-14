package com.lianyu.web.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@code /internal/**} 的鉴权。Sa-Token 只拦 {@code /api/**}，所以内部接口必须自己戴锁。
 *
 * <p>签名口径：{@code HMAC-SHA256(secret, ts \n nonce \n method \n path \n sha256Hex(body))}。
 * 为什么带上 body 摘要：不带的话，同一秒内换个请求体可以重放，绑定码就能被人捡走。
 */
@Slf4j
@Component
public class InternalSignatureFilter extends OncePerRequestFilter {

    private static final String PATH_PREFIX = "/internal/";
    private static final long TOLERANCE_MS = 300_000L;
    private static final long NONCE_TTL_MS = 600_000L;

    private final byte[] secret;
    private final Map<String, Long> seenNonces = new ConcurrentHashMap<>();

    public InternalSignatureFilter(@Value("${lianyu.qq.gateway-secret:}") String secret) {
        this.secret = secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8);
        if (this.secret.length == 0) {
            log.warn("lianyu.qq.gateway-secret 未配置：/internal/** 一律拒绝（fail-closed）");
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(PATH_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (secret.length == 0) {
            reject(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "internal channel disabled");
            return;
        }
        CachedBodyHttpServletRequest cached = new CachedBodyHttpServletRequest(request);
        String ts = request.getHeader("x-qq-ts");
        String nonce = request.getHeader("x-qq-nonce");
        String sign = request.getHeader("x-qq-sign");
        if (ts == null || nonce == null || sign == null) {
            reject(response, HttpServletResponse.SC_UNAUTHORIZED, "missing signature headers");
            return;
        }
        long stamp;
        try {
            stamp = Long.parseLong(ts);
        } catch (NumberFormatException e) {
            reject(response, HttpServletResponse.SC_UNAUTHORIZED, "bad timestamp");
            return;
        }
        if (Math.abs(System.currentTimeMillis() - stamp) > TOLERANCE_MS) {
            reject(response, HttpServletResponse.SC_UNAUTHORIZED, "timestamp out of window");
            return;
        }
        purgeExpiredNonces();
        if (seenNonces.putIfAbsent(nonce, System.currentTimeMillis()) != null) {
            reject(response, HttpServletResponse.SC_UNAUTHORIZED, "replayed nonce");
            return;
        }

        String canonical = ts + "\n" + nonce + "\n" + request.getMethod() + "\n"
                + request.getRequestURI() + "\n" + sha256Hex(cached.getBody());
        String expected = hmacHex(canonical);
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                sign.toLowerCase().getBytes(StandardCharsets.UTF_8))) {
            log.warn("internal signature mismatch: uri={}, remote={}", request.getRequestURI(), request.getRemoteAddr());
            reject(response, HttpServletResponse.SC_UNAUTHORIZED, "bad signature");
            return;
        }
        chain.doFilter(cached, response);
    }

    private String hmacHex(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return toHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("hmac init failed", e);
        }
    }

    private static String sha256Hex(byte[] body) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return toHex(digest.digest(body == null ? new byte[0] : body));
        } catch (Exception e) {
            throw new IllegalStateException("sha256 init failed", e);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    private void purgeExpiredNonces() {
        if (seenNonces.size() < 1024) {
            return;
        }
        long cutoff = System.currentTimeMillis() - NONCE_TTL_MS;
        seenNonces.entrySet().removeIf(e -> e.getValue() < cutoff);
    }

    private static void reject(HttpServletResponse response, int status, String reason) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":" + status + ",\"message\":\"" + reason + "\"}");
    }
}
