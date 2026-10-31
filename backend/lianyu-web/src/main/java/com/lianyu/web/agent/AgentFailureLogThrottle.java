package com.lianyu.web.agent;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 主动关怀失败的日志节流器。
 *
 * <p>用户 key 欠费（402）/失效（401）属于「用户侧配置问题」，会随每次调度重复出现；
 * 每轮都打一条 WARN 会让真实的上游 5xx 淹没在日志里（2026-10-22 语音回归期间
 * 观察到的 `proactiveTurn error: model gateway http 402` 刷屏）。按
 * (userId, errorCode) 做时间窗去重：窗内只降级到 DEBUG，不丢信息也不刷屏。
 *
 * <p>抽成独立类是为了能直接单测时间窗与容量上限，不必启动 RestClient。
 */
class AgentFailureLogThrottle {

    private final long intervalMs;
    private final int maxKeys;
    private final Map<String, Long> lastLoggedAt = new ConcurrentHashMap<>();

    AgentFailureLogThrottle(long intervalMs, int maxKeys) {
        this.intervalMs = intervalMs;
        this.maxKeys = maxKeys;
    }

    /**
     * @return true = 本次应该 WARN（窗内首次）；false = 已被抑制，应降级到 DEBUG
     */
    boolean allow(long userId, String code, long nowMs) {
        String key = userId + "|" + (code == null || code.isEmpty() ? "unknown" : code);
        Long last = lastLoggedAt.get(key);
        if (last != null && nowMs - last < intervalMs) {
            return false;
        }
        // 防御性上限：用户量大时不让这张表无界增长。
        if (lastLoggedAt.size() > maxKeys) {
            lastLoggedAt.clear();
        }
        lastLoggedAt.put(key, nowMs);
        return true;
    }
}
