package com.lianyu.service.config;

import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * AI 调用专用有界线程池（前台 / 后台隔离）。
 *
 * <p>chatStream 的 {@code blockLast()} 与 chatBlocking 的同步 {@code chatModel.call}
 * 都是阻塞式 AI HTTP I/O。若不传 Executor，会落到 {@code ForkJoinPool.commonPool()}，
 * 高并发时饿死全 JVM 其它异步任务。
 *
 * <p>前台（SSE / 语音）与后台（朋友圈评论 / 日记 / 记忆摘要）必须分池：后台风暴曾把
 * 共享池打满（32 active + 200 queue）导致前台 {@code RejectedExecutionException}，
 * 用户侧表现为「发消息角色不回复也不报错」。
 */
@Configuration
public class AiStreamExecutorConfig {

    @Bean(name = "aiStreamExecutor")
    public TaskExecutor aiStreamExecutor(
            @Value("${lianyu.ai.executor.core-pool-size:12}") int corePoolSize,
            @Value("${lianyu.ai.executor.max-pool-size:48}") int maxPoolSize,
            @Value("${lianyu.ai.executor.queue-capacity:150}") int queueCapacity) {
        return build("ai-stream-", corePoolSize, maxPoolSize, queueCapacity);
    }

    /**
     * Background AI I/O pool. Sized near {@code resilience4j.bulkhead.ai-background}
     * so moments/diary storms cannot starve interactive SSE.
     */
    @Bean(name = "aiBackgroundExecutor")
    public TaskExecutor aiBackgroundExecutor(
            @Value("${lianyu.ai.background-executor.core-pool-size:4}") int corePoolSize,
            @Value("${lianyu.ai.background-executor.max-pool-size:8}") int maxPoolSize,
            @Value("${lianyu.ai.background-executor.queue-capacity:32}") int queueCapacity) {
        return build("ai-bg-", corePoolSize, maxPoolSize, queueCapacity);
    }

    private static TaskExecutor build(String prefix, int core, int max, int queue) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix(prefix);
        executor.setCorePoolSize(Math.max(2, core));
        executor.setMaxPoolSize(Math.max(core, max));
        executor.setQueueCapacity(Math.max(8, queue));
        // Prefer fail-fast over unbounded wait; callers map RejectedExecution → 繁忙提示.
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }
}
