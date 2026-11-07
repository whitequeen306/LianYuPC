package com.lianyu.service.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 模型额度熔断器：把「这个用户的 key 现在还能不能用」变成一个可读、可自愈的状态。
 *
 * <p>为什么需要它：主动关怀是定时任务，用户 key 欠费（402）/失效（401）/模型名写错（404）时，
 * 每轮扫描都会失败一次，而失败路径不会写冷却键——于是同一个会话每 30 秒重试一次，
 * 一天刷出几百条 402（2026-11-07 实测：4 个用户 255 次，其中一人 121 次）。
 * 更糟的是：用户充值成功后，这些重试会立刻开始真实计费。
 *
 * <p>状态机（每个用户一把 Redis key，值里带失败次数）：
 * <pre>
 *   ok ──(额度类错误)──▶ open(until=now+backoff) ──(到期)──▶ 半开放行一次 ──成功──▶ ok
 *                                        ▲                         │
 *                                        └──────────失败───────────┘（backoff 翻倍）
 * </pre>
 *
 * <p>恢复不需要轮询：任何一次真实成功（聊天或主动关怀）都会把熔断键删掉，主动关怀立刻恢复。
 * 这就是「额度为 0 自动关、额度恢复自动开」的全部机制——不需要用户手动开关。
 */
@Slf4j
@Service
public class ModelQuotaGuard {

    /** 额度类错误：出现即熔断。模型名错 / key 失效也归到这里，因为它们同样会每轮失败。 */
    private static final Set<String> BLOCKING_CODES = Set.of(
            "provider_insufficient_balance",
            "provider_invalid_api_key",
            "provider_unauthorized",
            "provider_model_not_found");

    /** 退避阶梯：首次 10 分钟，之后翻倍并封顶 24 小时。 */
    private static final long[] BACKOFF_SECONDS = {
            Duration.ofMinutes(10).toSeconds(),
            Duration.ofMinutes(30).toSeconds(),
            Duration.ofHours(2).toSeconds(),
            Duration.ofHours(6).toSeconds(),
            Duration.ofHours(24).toSeconds()
    };

    public static final String STATE_OK = "ok";
    public static final String STATE_BLOCKED = "blocked";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public ModelQuotaGuard(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    static String key(long userId) {
        return "ai:quota:block:" + userId;
    }

    static boolean isBlockingCode(String code) {
        return code != null && BLOCKING_CODES.contains(code.toLowerCase());
    }

    /** 退避时长（秒）：第 n 次失败取第 n-1 档，超出取最后一档。 */
    static long backoffSeconds(int fails) {
        int idx = Math.max(0, Math.min(fails - 1, BACKOFF_SECONDS.length - 1));
        return BACKOFF_SECONDS[idx];
    }

    /**
     * 现在能不能发起调用。熔断键在 TTL 到期后自然消失——那一刻的第一次调用就是"半开探测"，
     * 成功即 {@link #recordSuccess}，失败即 {@link #recordFailure} 重新熔断。
     */
    public boolean allow(long userId) {
        try {
            return !Boolean.TRUE.equals(redisTemplate.hasKey(key(userId)));
        } catch (Exception e) {
            // Redis 挂了不能把整条链路堵死：宁可让它发出去，也不要因为监控组件故障而停聊
            log.warn("model quota guard unavailable, allowing call: userId={}, reason={}",
                    userId, e.toString());
            return true;
        }
    }

    /**
     * 记录一次额度类失败，把用户熔断掉。
     *
     * @param code  Python 网关的错误码（如 provider_insufficient_balance）
     * @param detail 人类可读细节（只入状态，不写日志全文，避免把 key 片段带出去）
     * @return 本次熔断时长（秒），非额度类错误返回 0
     */
    public long recordFailure(long userId, String code, String detail) {
        if (!isBlockingCode(code)) {
            return 0;
        }
        int fails = readFails(userId) + 1;
        long seconds = backoffSeconds(fails);
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("state", STATE_BLOCKED);
        state.put("code", code);
        state.put("detail", detail == null ? "" : detail);
        state.put("fails", fails);
        state.put("blockedUntil", System.currentTimeMillis() + seconds * 1000L);
        try {
            redisTemplate.opsForValue()
                    .set(key(userId), objectMapper.writeValueAsString(state), Duration.ofSeconds(seconds));
        } catch (Exception e) {
            log.warn("model quota guard write failed: userId={}, reason={}", userId, e.toString());
            return 0;
        }
        log.info("model quota blocked: userId={}, code={}, fails={}, forSeconds={}",
                userId, code, fails, seconds);
        return seconds;
    }

    /** 任意一次成功都视为额度已恢复：删键即恢复主动关怀，不需要等冷却。 */
    public void recordSuccess(long userId) {
        try {
            if (Boolean.TRUE.equals(redisTemplate.hasKey(key(userId)))) {
                redisTemplate.delete(key(userId));
                log.info("model quota recovered: userId={}", userId);
            }
        } catch (Exception e) {
            log.warn("model quota guard clear failed: userId={}, reason={}", userId, e.toString());
        }
    }

    /** 给接口/前端看的状态快照；不暴露 code 之外的任何凭据信息。 */
    public Map<String, Object> snapshot(long userId) {
        Map<String, Object> out = new LinkedHashMap<>();
        String raw = null;
        try {
            raw = redisTemplate.opsForValue().get(key(userId));
        } catch (Exception e) {
            out.put("state", STATE_OK);
            out.put("degraded", true);
            return out;
        }
        if (raw == null || raw.isBlank()) {
            out.put("state", STATE_OK);
            out.put("proactiveEnabled", true);
            return out;
        }
        JsonNode node;
        try {
            node = objectMapper.readTree(raw);
        } catch (Exception e) {
            out.put("state", STATE_BLOCKED);
            out.put("proactiveEnabled", false);
            return out;
        }
        long until = node.path("blockedUntil").asLong(0L);
        out.put("state", STATE_BLOCKED);
        out.put("code", node.path("code").asText(""));
        out.put("fails", node.path("fails").asInt(1));
        out.put("proactiveEnabled", false);
        out.put("retryAfterSeconds", Math.max(0L, (until - System.currentTimeMillis()) / 1000L));
        return out;
    }

    private int readFails(long userId) {
        try {
            String raw = redisTemplate.opsForValue().get(key(userId));
            if (raw == null || raw.isBlank()) {
                return 0;
            }
            return objectMapper.readTree(raw).path("fails").asInt(0);
        } catch (Exception e) {
            return 0;
        }
    }
}
