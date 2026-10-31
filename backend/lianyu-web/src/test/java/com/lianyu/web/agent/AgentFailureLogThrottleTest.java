package com.lianyu.web.agent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AgentFailureLogThrottleTest {

    @Test
    void firstFailureForUserAndCodeIsLogged() {
        AgentFailureLogThrottle throttle = new AgentFailureLogThrottle(60_000L, 8);
        assertTrue(throttle.allow(1L, "provider_insufficient_balance", 1_000L));
    }

    @Test
    void repeatsInsideWindowAreSuppressed() {
        AgentFailureLogThrottle throttle = new AgentFailureLogThrottle(60_000L, 8);
        assertTrue(throttle.allow(1L, "provider_insufficient_balance", 1_000L));
        assertFalse(throttle.allow(1L, "provider_insufficient_balance", 2_000L));
        assertFalse(throttle.allow(1L, "provider_insufficient_balance", 60_999L));
    }

    @Test
    void sameCodeAfterWindowIsLoggedAgain() {
        AgentFailureLogThrottle throttle = new AgentFailureLogThrottle(60_000L, 8);
        assertTrue(throttle.allow(1L, "provider_insufficient_balance", 1_000L));
        assertTrue(throttle.allow(1L, "provider_insufficient_balance", 61_000L));
    }

    @Test
    void differentUserOrCodeIsTrackedSeparately() {
        AgentFailureLogThrottle throttle = new AgentFailureLogThrottle(60_000L, 8);
        assertTrue(throttle.allow(1L, "provider_insufficient_balance", 1_000L));
        // 另一个用户欠费不能被前一个用户的节流吃掉
        assertTrue(throttle.allow(2L, "provider_insufficient_balance", 1_000L));
        // 同一个用户的另一种失败（如 401）也应单独记一次
        assertTrue(throttle.allow(1L, "provider_auth_failed", 1_000L));
    }

    @Test
    void emptyCodeFallsBackToUnknownBucket() {
        AgentFailureLogThrottle throttle = new AgentFailureLogThrottle(60_000L, 8);
        assertTrue(throttle.allow(1L, "", 1_000L));
        assertFalse(throttle.allow(1L, null, 2_000L));
    }

    @Test
    void tableDoesNotGrowWithoutBound() {
        AgentFailureLogThrottle throttle = new AgentFailureLogThrottle(60_000L, 3);
        for (long userId = 1; userId <= 10; userId++) {
            assertTrue(throttle.allow(userId, "provider_insufficient_balance", userId * 1_000L));
        }
        // 超过上限后清空重建：最近一次仍然会放行，不会因为「已抑制」而永久丢失告警
        assertTrue(throttle.allow(11L, "provider_insufficient_balance", 20_000L));
    }
}
