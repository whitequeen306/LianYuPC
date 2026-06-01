package com.lianyu.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * LianYu-PC 后端入口：聚合 web / service / dao 等模块，扫描 {@code com.lianyu} 包。
 * <p>
 * 聊天用 OpenAI/Ollama 客户端由 {@code AiChatService} 按用户 Vault 动态创建，
 * 因此排除 Spring AI 对 application.yml 中 API Key 的自动装配，避免与 BYOK 冲突。
 */
@EnableAsync
@EnableScheduling
@SpringBootApplication(scanBasePackages = "com.lianyu",
        exclude = {
                org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration.class,
                org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingAutoConfiguration.class,
                org.springframework.ai.model.openai.autoconfigure.OpenAiImageAutoConfiguration.class,
                org.springframework.ai.model.openai.autoconfigure.OpenAiAudioSpeechAutoConfiguration.class,
                org.springframework.ai.model.openai.autoconfigure.OpenAiAudioTranscriptionAutoConfiguration.class,
                org.springframework.ai.model.openai.autoconfigure.OpenAiModerationAutoConfiguration.class,
                org.springframework.ai.model.ollama.autoconfigure.OllamaChatAutoConfiguration.class,
                org.springframework.ai.model.ollama.autoconfigure.OllamaEmbeddingAutoConfiguration.class
        })
public class LianYuApplication {

    public static void main(String[] args) {
        SpringApplication.run(LianYuApplication.class, args);
    }
}
