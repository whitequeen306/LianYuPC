package com.lianyu.ai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

@Data
@Configuration
@ConfigurationProperties(prefix = "lianyu.ai")
public class AiConfig {

    @Bean
    public ScheduledExecutorService scheduledExecutorService() {
        return Executors.newScheduledThreadPool(2);
    }

    private int contextWindow = 20;

    // embedding / reranker 配置已移除：向量化与重排整体在 Python（backend-agent），
    // Java 侧不再构造任何 DashScope 向量/重排请求。
}
