package com.lianyu.service.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.lianyu.service.dto.VaultEntryResponse;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Per-upstream breaker isolation: tripping one upstream must not affect others,
 * and BusinessException must be ignored (not counted as a failure).
 */
class UpstreamCircuitBreakerFactoryTest {

    private UpstreamCircuitBreakerFactory factory;

    @BeforeEach
    void setUp() {
        CircuitBreakerConfig cfg = CircuitBreakerConfig.custom()
                .slidingWindowSize(20)
                .minimumNumberOfCalls(5)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .build();
        Map<String, CircuitBreakerConfig> configs = new HashMap<>();
        configs.put(UpstreamCircuitBreakerFactory.FALLBACK_NAME, cfg);
        factory = new UpstreamCircuitBreakerFactory(CircuitBreakerRegistry.of(configs));
    }

    private static VaultEntryResponse vault(String provider, String baseUrl) {
        return VaultEntryResponse.builder().provider(provider).baseUrl(baseUrl).build();
    }

    @Test
    void distinctUpstreamsGetDistinctBreakers_normalizationApplies() {
        CircuitBreaker a = factory.resolve(vault("DEEPSEEK", "https://api.deepseek.com/"));
        CircuitBreaker a2 = factory.resolve(vault("deepseek", "https://api.deepseek.com"));
        CircuitBreaker b = factory.resolve(vault("openai", "https://api.openai.com"));

        // same upstream after lowercase + trailing-slash normalization -> same breaker
        assertThat(a).isSameAs(a2);
        assertThat(a).isNotSameAs(b);
        assertThat(a.getName()).isEqualTo("ai-upstream|deepseek|https://api.deepseek.com");
    }

    @Test
    void trippingUpstreamA_doesNotAffectUpstreamB() {
        CircuitBreaker a = factory.resolve(vault("deepseek", "https://api.deepseek.com"));
        CircuitBreaker b = factory.resolve(vault("openai", "https://api.openai.com"));

        // Force A open (one upstream's outage).
        a.transitionToForcedOpenState();
        assertThat(a.tryAcquirePermission()).isFalse(); // A fast-fails

        // B untouched: still closed and permits calls.
        assertThat(b.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(b.tryAcquirePermission()).isTrue();

        // Fallback/global breaker also unaffected by A's forced-open.
        assertThat(factory.resolve(null).tryAcquirePermission()).isTrue();
    }

    @Test
    void upstreamBreakerInheritsTemplateThresholds() {
        CircuitBreaker a = factory.resolve(vault("deepseek", "https://api.deepseek.com"));
        CircuitBreaker fallback = factory.resolve(null);
        // Per-upstream instances reuse the shared ai-chat parameter template.
        assertThat(a.getCircuitBreakerConfig().getMinimumNumberOfCalls())
                .isEqualTo(fallback.getCircuitBreakerConfig().getMinimumNumberOfCalls());
        assertThat(a.getCircuitBreakerConfig().getFailureRateThreshold())
                .isEqualTo(fallback.getCircuitBreakerConfig().getFailureRateThreshold());
        assertThat(a.getCircuitBreakerConfig().getSlidingWindowSize())
                .isEqualTo(fallback.getCircuitBreakerConfig().getSlidingWindowSize());
    }

    @Test
    void nullOrBlankVault_fallsBackToGlobalBreaker() {
        CircuitBreaker blank = factory.resolve(null);
        CircuitBreaker empty = factory.resolve(vault("", ""));
        assertThat(blank.getName()).isEqualTo(UpstreamCircuitBreakerFactory.FALLBACK_NAME);
        assertThat(empty).isSameAs(blank);
    }
}
