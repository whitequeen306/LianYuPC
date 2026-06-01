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
    private int embeddingDim = 1536;

    private final Embedding embedding = new Embedding();
    private final Reranker reranker = new Reranker();

    @Data
    public static class Embedding {
        private String model = "text-embedding-v3";
        private String apiKey;
        private String baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";
    }

    @Data
    public static class Reranker {
        private String model = "gte-rerank-v2";
        private String apiKey;
        private String url = "https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank";
    }
}
