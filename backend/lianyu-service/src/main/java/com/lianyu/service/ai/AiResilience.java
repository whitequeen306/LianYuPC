package com.lianyu.service.ai;

import com.lianyu.common.base.ErrorCode;
import com.lianyu.common.exception.BusinessException;
import com.lianyu.service.dto.AiChatRequest;
import com.lianyu.service.dto.VaultEntryResponse;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * AI 出站韧性集中地：前台/后台两条 lane（bulkhead + executor）、timeLimiter、
 * 全局与按 upstream 隔离的熔断器（{@link UpstreamCircuitBreakerFactory}）。
 * 流式入口在拿不到隔舱/熔断许可时必须 fast-fail，避免占死线程（issue #4 / #22）。
 */
@Slf4j
@Component
public class AiResilience {

    private static final String RESILIENCE_NAME = "ai-chat";
    private static final String BACKGROUND_BULKHEAD_NAME = "ai-background";

    /** 前台交互（SSE / 语音 / 用户可见破冰等） */
    private final Bulkhead interactiveBulkhead;
    /** 后台任务（朋友圈 / 日记 / 记忆摘要 / 会话摘要 / @裁决等） */
    private final Bulkhead backgroundBulkhead;
    private final TimeLimiter timeLimiter;
    /** Fallback/global breaker; per-upstream breakers resolved via {@link #upstreamBreakers}. */
    private final CircuitBreaker circuitBreaker;
    private final UpstreamCircuitBreakerFactory upstreamBreakers;
    private final ScheduledExecutorService scheduler;
    /** Foreground SSE / voice / interactive blocking. */
    private final Executor aiStreamExecutor;
    /** Background moments / diary / memory — isolated so storms cannot starve chat. */
    private final Executor aiBackgroundExecutor;

    public AiResilience(BulkheadRegistry bulkheadRegistry,
                        TimeLimiterRegistry timeLimiterRegistry,
                        CircuitBreakerRegistry circuitBreakerRegistry,
                        ScheduledExecutorService scheduler,
                        @Qualifier("aiStreamExecutor") Executor aiStreamExecutor,
                        @Qualifier("aiBackgroundExecutor") Executor aiBackgroundExecutor) {
        this.interactiveBulkhead = bulkheadRegistry.bulkhead(RESILIENCE_NAME);
        this.backgroundBulkhead = bulkheadRegistry.bulkhead(BACKGROUND_BULKHEAD_NAME);
        this.timeLimiter = timeLimiterRegistry.timeLimiter(RESILIENCE_NAME);
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker(RESILIENCE_NAME);
        this.upstreamBreakers = new UpstreamCircuitBreakerFactory(circuitBreakerRegistry);
        this.scheduler = scheduler;
        this.aiStreamExecutor = aiStreamExecutor;
        this.aiBackgroundExecutor = aiBackgroundExecutor;
    }

    public Bulkhead interactiveBulkhead() {
        return interactiveBulkhead;
    }

    public TimeLimiter timeLimiter() {
        return timeLimiter;
    }

    public ScheduledExecutorService scheduler() {
        return scheduler;
    }

    /** 前台交互走 {@code ai-chat}；{@code background=true} 走 {@code ai-background}。 */
    public Bulkhead resolveBulkhead(AiChatRequest request) {
        if (request != null && request.isBackground()) {
            return backgroundBulkhead;
        }
        return interactiveBulkhead;
    }

    /** Background AI must not share the interactive SSE pool. */
    public Executor resolveAiExecutor(AiChatRequest request) {
        if (request != null && request.isBackground()) {
            return aiBackgroundExecutor;
        }
        return aiStreamExecutor;
    }

    /** Per-upstream circuit breaker keyed by (provider + baseUrl); falls back to global when unresolved. */
    public CircuitBreaker resolveBreaker(VaultEntryResponse vault) {
        return upstreamBreakers.resolve(vault);
    }

    /**
     * Fast-fail stream calls when this upstream's breaker is OPEN. Delegates to
     * {@code tryAcquirePermission()} (no metrics recorded) so a single user's dead
     * upstream doesn't drag down every other user's AI calls.
     */
    public void acquireUpstreamPermit(CircuitBreaker cb, VaultEntryResponse vault) {
        if (!cb.tryAcquirePermission()) {
            String upstream = (vault == null) ? "unknown"
                    : String.join("|",
                            vault.getProvider() == null ? "" : vault.getProvider(),
                            vault.getBaseUrl() == null ? "" : vault.getBaseUrl());
            log.warn("AI upstream circuit breaker OPEN ({}), fast-fail stream: {}",
                    cb.getName(), upstream);
            throw new BusinessException(ErrorCode.AI_PROVIDER_ERROR, "对方模型暂时繁忙，请稍后再试");
        }
    }

    public void releaseUpstreamPermit(CircuitBreaker cb, long startNanos, Throwable error) {
        long duration = System.nanoTime() - startNanos;
        if (error == null) {
            cb.onSuccess(duration, java.util.concurrent.TimeUnit.NANOSECONDS);
            return;
        }
        Throwable e = error;
        for (int depth = 0; depth < 6 && e != null; depth++) {
            if (e instanceof BusinessException) {
                cb.onSuccess(duration, java.util.concurrent.TimeUnit.NANOSECONDS);
                return;
            }
            e = e.getCause();
        }
        cb.onError(duration, java.util.concurrent.TimeUnit.NANOSECONDS, error);
    }

    public static Throwable unwrap(Throwable e) {
        Throwable cur = e;
        for (int i = 0; i < 6 && cur != null; i++) {
            if (cur instanceof BusinessException
                    || cur instanceof RejectedExecutionException
                    || cur instanceof io.github.resilience4j.bulkhead.BulkheadFullException) {
                return cur;
            }
            if (cur.getCause() == null || cur.getCause() == cur) {
                break;
            }
            cur = cur.getCause();
        }
        return e.getCause() != null ? e.getCause() : e;
    }
}
