package com.lianyu.service.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class GroupChatExecutorConfig {

    @Bean(name = "groupChatExecutor")
    public TaskExecutor groupChatExecutor(
            @Value("${lianyu.group.executor.core-pool-size:8}") int corePoolSize,
            @Value("${lianyu.group.executor.max-pool-size:20}") int maxPoolSize,
            @Value("${lianyu.group.executor.queue-capacity:300}") int queueCapacity) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("group-chat-");
        executor.setCorePoolSize(Math.max(2, corePoolSize));
        executor.setMaxPoolSize(Math.max(corePoolSize, maxPoolSize));
        executor.setQueueCapacity(Math.max(50, queueCapacity));
        executor.initialize();
        return executor;
    }
}
