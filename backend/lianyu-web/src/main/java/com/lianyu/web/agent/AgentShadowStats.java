package com.lianyu.web.agent;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * SHADOW 模式的漂移率统计。
 *
 * <p>为什么需要：切 AGENT 的前提是「Python 与 Java 的产出一致到可接受」，
 * 但现在只有逐条日志（`Shadow observe[1]: DIFF ...`），没有汇总。
 * 要判断「漂移 12%」还是「漂移 60%」得自己数日志 —— 等于没有可观测性，
 * 也就没有依据从 SHADOW 走向 AGENT。
 *
 * <p>口径（与 `scripts/agent-shadow-report.ps1` 一致）：
 * <pre>
 *   漂移率 = DIFF / (match + DIFF)
 * </pre>
 * **不含 both-null** —— 两侧都没结果说明这条链路本身没触发，
 * 计入分母会稀释真实漂移。
 */
@Slf4j
@Component
public class AgentShadowStats {

    /** 链路 → 计数。key 形如 `observe` / `proactive:PROACTIVE`。 */
    private final Map<String, Counters> stats = new ConcurrentHashMap<>();

    public static final class Counters {
        final AtomicLong match = new AtomicLong();
        final AtomicLong diff = new AtomicLong();
        final AtomicLong bothNull = new AtomicLong();

        long total() {
            return match.get() + diff.get();
        }

        double driftRate() {
            long denom = total();
            return denom == 0 ? 0.0 : (diff.get() * 100.0) / denom;
        }
    }

    public void recordMatch(String channel) {
        counters(channel).match.incrementAndGet();
    }

    public void recordDiff(String channel) {
        counters(channel).diff.incrementAndGet();
    }

    public void recordBothNull(String channel) {
        counters(channel).bothNull.incrementAndGet();
    }

    private Counters counters(String channel) {
        return stats.computeIfAbsent(channel, k -> new Counters());
    }

    /** 每 5 分钟打一次汇总；没有样本时保持安静。 */
    @Scheduled(fixedDelayString = "${lianyu.agent.shadow-stats-interval-ms:300000}",
            initialDelayString = "${lianyu.agent.shadow-stats-initial-delay-ms:60000}")
    public void logSummary() {
        stats.forEach((channel, counters) -> {
            long total = counters.total();
            if (total == 0 && counters.bothNull.get() == 0) {
                return;
            }
            log.info("Shadow drift[{}]: match={}, diff={}, both-null={}, drift={}%",
                    channel, counters.match.get(), counters.diff.get(),
                    counters.bothNull.get(), String.format("%.1f", counters.driftRate()));
        });
    }

    /** 快照（测试与运维排查用）。 */
    public Map<String, String> snapshot() {
        Map<String, String> result = new ConcurrentHashMap<>();
        stats.forEach((channel, counters) -> result.put(channel,
                String.format("match=%d diff=%d bothNull=%d drift=%.1f%%",
                        counters.match.get(), counters.diff.get(),
                        counters.bothNull.get(), counters.driftRate())));
        return result;
    }

    /** 测试用：清空计数。 */
    public void reset() {
        stats.clear();
    }
}
