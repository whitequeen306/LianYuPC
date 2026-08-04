package com.lianyu.service.ai;

import com.lianyu.service.dto.VaultEntryResponse;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Per-upstream AI circuit breaker: one breaker per (provider + baseUrl).
 * Isolates an upstream failure (a user's DNS/network/provider outage) so it
 * trips only that upstream's breaker instead of the global "ai-chat" one.
 */
@Component
public class UpstreamCircuitBreakerFactory {

    /** Fallback breaker when the upstream cannot be determined (vault unresolved). */
    public static final String FALLBACK_NAME = "ai-chat";

    /** Defensive cap on distinct upstream breakers; beyond this we reuse the fallback. */
    private static final int MAX_UPSTREAM_BREAKERS = 500;

    private final CircuitBreakerRegistry registry;
    private final CircuitBreaker fallback;

    public UpstreamCircuitBreakerFactory(CircuitBreakerRegistry registry) {
        this.registry = registry;
        this.fallback = registry.circuitBreaker(FALLBACK_NAME);
    }

    /** Breaker for the given vault; falls back to the global one when vault is null/blank. */
    public CircuitBreaker resolve(VaultEntryResponse vault) {
        if (vault == null) {
            return fallback;
        }
        String key = key(vault);
        if (key == null) {
            return fallback;
        }
        if (registry.getAllCircuitBreakers().size() >= MAX_UPSTREAM_BREAKERS) {
            return fallback;
        }
        return registry.circuitBreaker(key, this::copyDefaultConfig);
    }

    /** Copy the shared "ai-chat" config so per-upstream instances use identical thresholds. */
    private CircuitBreakerConfig copyDefaultConfig() {
        return fallback.getCircuitBreakerConfig();
    }

    /** Normalized key: provider|baseUrl, lowercased, trailing slashes stripped. */
    static String key(VaultEntryResponse vault) {
        String provider = vault.getProvider() == null ? "" : vault.getProvider().trim().toLowerCase(Locale.ROOT);
        String base = vault.getBaseUrl() == null ? "" : vault.getBaseUrl().trim().toLowerCase(Locale.ROOT);
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (provider.isEmpty() && base.isEmpty()) {
            return null;
        }
        return "ai-upstream|" + provider + "|" + base;
    }
}
