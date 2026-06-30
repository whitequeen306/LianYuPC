package com.lianyu.service.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * AI 流式 / 阻塞调用的专用有界线程池。
 *
 * chatStream 的 blockLast() 与 chatBlocking 的同步 chatModel.call 都是阻塞式 AI HTTP I/O；
 * 若用 CompletableFuture.runAsync/supplyAsync 不传 Executor，会落到 ForkJoinPool.commonPool()
 * （并发 = CPU 核 - 1），高并发流式对话时占满公共池、饿死全 JVM 其它异步任务（issue #12）。
 * bulkhead 已把 AI 并发限制在 16，故该池仅承接已获许可的任务，按 groupChatExecutor 同风格配置。
 */
@Configuration
public class AiStreamExecutorConfig {

    @Bean(name = "aiStreamExecutor")
    public TaskExecutor aiStreamExecutor(
            @Value("${lianyu.ai.executor.core-pool-size:8}") int corePoolSize,
            @Value("${lianyu.ai.executor.max-pool-size:32}") int maxPoolSize,
            @Value("${lianyu.ai.executor.queue-capacity:200}") int queueCapacity) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("ai-stream-");
        executor.setCorePoolSize(Math.max(2, corePoolSize));
        executor.setMaxPoolSize(Math.max(corePoolSize, maxPoolSize));
        executor.setQueueCapacity(Math.max(50, queueCapacity));
        executor.initialize();
        return executor;
    }
}
