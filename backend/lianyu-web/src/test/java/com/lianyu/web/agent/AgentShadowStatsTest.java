package com.lianyu.web.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * SHADOW 漂移率统计测试。
 *
 * <p>口径必须与 `scripts/agent-shadow-report.ps1` 一致：
 * 漂移率 = diff / (match + diff)，**不含 both-null**。
 * 口径错了会给出「看起来很好」的假指标，进而误判可以切 AGENT。
 */
class AgentShadowStatsTest {

    private AgentShadowStats stats;

    @BeforeEach
    void setUp() {
        stats = new AgentShadowStats();
    }

    @Test
    void driftRateExcludesBothNull() {
        stats.recordMatch("observe");
        stats.recordDiff("observe");
        // 5 次 both-null 不应稀释漂移率
        for (int i = 0; i < 5; i++) {
            stats.recordBothNull("observe");
        }
        assertTrue(stats.snapshot().get("observe").contains("drift=50.0%"));
    }

    @Test
    void zeroSamplesIsZeroDrift() {
        stats.recordBothNull("proactive:PROACTIVE");
        assertTrue(stats.snapshot().get("proactive:PROACTIVE").contains("drift=0.0%"));
    }

    @Test
    void channelsAreIsolated() {
        stats.recordMatch("observe");
        stats.recordDiff("proactive:COLD_OPEN");
        stats.recordDiff("proactive:COLD_OPEN");

        assertEquals(true, stats.snapshot().get("observe").contains("drift=0.0%"));
        assertEquals(true, stats.snapshot().get("proactive:COLD_OPEN").contains("drift=100.0%"));
    }

    @Test
    void allDiffIsHundredPercent() {
        for (int i = 0; i < 10; i++) {
            stats.recordDiff("observe");
        }
        assertTrue(stats.snapshot().get("observe").contains("drift=100.0%"));
    }

    @Test
    void summaryIsSilentWithoutSamples() {
        // 不抛异常即可；无样本时不应产生日志噪声
        stats.logSummary();
        assertTrue(stats.snapshot().isEmpty());
    }

    @Test
    void resetClearsCounters() {
        stats.recordMatch("observe");
        stats.reset();
        assertTrue(stats.snapshot().isEmpty());
    }
}
