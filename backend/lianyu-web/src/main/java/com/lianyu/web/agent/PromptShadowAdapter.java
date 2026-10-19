package com.lianyu.web.agent;

import com.lianyu.service.agent.AssemblySnapshot;
import com.lianyu.service.agent.PromptShadowPort;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * {@link PromptShadowPort} 实现：把比对丢到独立小线程池，永不阻塞请求线程。
 *
 * <p>为什么单独一个线程池而不是复用 AI 池：影子比对是**可丢弃**的负载，
 * 不该和用户请求抢 AI 池的槽位（AI 池饱和时的策略是拒绝，影子比对被拒无所谓）。
 * 队列满时直接丢弃并计数，不排队 —— 保证影子从不反压主链路。
 */
@Slf4j
@Component
public class PromptShadowAdapter implements PromptShadowPort {

    private final PromptShadowComparator comparator;
    private final ExecutorService executor;
    private final AtomicInteger dropped = new AtomicInteger();
    private final boolean shadowMode;

    public PromptShadowAdapter(
            PromptShadowComparator comparator,
            @Autowired(required = false) AgentRuntimeClient client,
            @Value("${lianyu.agent.prompt-shadow.enabled:true}") boolean enabled,
            @Value("${lianyu.agent.mode:OFF}") String mode) {
        this.comparator = comparator;
        // 影子比对只在 SHADOW 模式有意义：AGENT 模式下 Java 已直接用 Python 的 prompt，
        // 比对自己等于自己（白跑一次 HTTP）。
        this.shadowMode = "SHADOW".equalsIgnoreCase(mode);
        this.executor = enabled && shadowMode
                ? Executors.newFixedThreadPool(1, r -> {
                    Thread thread = new Thread(r, "prompt-shadow");
                    thread.setDaemon(true);
                    return thread;
                })
                : null;
    }

    @Override
    public void compareAsync(long userId, AssemblySnapshot snapshot, String javaPrompt) {
        ExecutorService pool = executor;
        if (pool == null || snapshot == null || javaPrompt == null) {
            return;
        }
        try {
            CompletableFuture.runAsync(() -> {
                try {
                    comparator.compareWithAgent(userId, snapshot, javaPrompt, true);
                } catch (Exception e) {
                    // 影子比对永不影响主链路
                    log.debug("Prompt shadow failed silently: {}", e.toString());
                }
            }, pool);
        } catch (Exception e) {
            dropped.incrementAndGet();
            log.debug("Prompt shadow rejected (pool saturated): {}", e.toString());
        }
    }

    /** 丢弃计数（运维观察用）。 */
    public int droppedCount() {
        return dropped.get();
    }

    @PreDestroy
    void shutdown() {
        if (executor != null) {
            executor.shutdownNow();
            try {
                executor.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
