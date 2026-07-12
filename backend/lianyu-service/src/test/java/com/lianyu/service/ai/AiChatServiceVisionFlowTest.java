package com.lianyu.service.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lianyu.service.dto.MessageDto;
import com.lianyu.service.rules.PromptRuleEngine;
import com.lianyu.service.storage.FileStorageService;
import com.lianyu.service.support.OutputLanguageService;
import com.lianyu.service.tools.ToolManager;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.ai.chat.messages.Message;

@ExtendWith(MockitoExtension.class)
class AiChatServiceVisionFlowTest {

    @Mock private ApiKeyVaultService vaultService;
    @Mock private FileStorageService fileStorageService;
    @Mock private ToolManager toolManager;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ScheduledExecutorService scheduler;
    @Mock private Executor aiStreamExecutor;
    @Mock private PromptRuleEngine promptRuleEngine;
    @Mock private OutputLanguageService outputLanguageService;

    private AiChatService service;

    @BeforeEach
    void setUp() {
        service = new AiChatService(
                vaultService,
                fileStorageService,
                toolManager,
                redisTemplate,
                new ObjectMapper(),
                BulkheadRegistry.ofDefaults(),
                TimeLimiterRegistry.ofDefaults(),
                CircuitBreakerRegistry.ofDefaults(),
                scheduler,
                aiStreamExecutor,
                promptRuleEngine,
                outputLanguageService);
    }

    @Test
    void buildImageAugmentedTextMessages_appendsStructuredVisionContext() {
        MessageDto system = new MessageDto();
        system.setRole("system");
        system.setContent("你是一个温柔角色。");

        MessageDto user = new MessageDto();
        user.setRole("user");
        user.setContent("帮我看看这张图");
        user.setImageUrl("/api/public/files/chat-images/demo.png");

        VisionAnalysisResult analysis = new VisionAnalysisResult("求识图", "high", "一只橘猫趴在窗台上");

        @SuppressWarnings("unchecked")
        List<Message> messages = (List<Message>) ReflectionTestUtils.invokeMethod(
                service,
                "buildImageAugmentedTextMessages",
                List.of(system, user),
                analysis);

        assertThat(messages).hasSize(2);
        assertThat(messages.get(0).getText())
                .contains("你是一个温柔角色。")
                .contains("[图片分析结果]")
                .contains("子意图: 求识图")
                .contains("可辨识度: high")
                .contains("客观描述: 一只橘猫趴在窗台上")
                .contains("不要输出 JSON");
        assertThat(messages.get(1).getText()).contains("帮我看看这张图");
    }

    @Test
    void buildImageAugmentedTextMessages_whenLowConfidence_addsHonestVisibilityRule() {
        MessageDto user = new MessageDto();
        user.setRole("user");
        user.setContent("这是什么");
        user.setImageUrl("/api/public/files/chat-images/demo.png");

        VisionAnalysisResult analysis = new VisionAnalysisResult("求识图", "看不清", "图像模糊，主体无法辨认");

        @SuppressWarnings("unchecked")
        List<Message> messages = (List<Message>) ReflectionTestUtils.invokeMethod(
                service,
                "buildImageAugmentedTextMessages",
                List.of(user),
                analysis);

        assertThat(messages).hasSize(2);
        assertThat(messages.get(0).getText())
                .contains("可辨识度: 看不清")
                .contains("必须如实告诉用户这张图看不太清");
    }

    @Test
    void buildDesktopGreetingPrompt_whenLowConfidence_includesHonestVisibilityRule() {
        VisionAnalysisResult analysis = new VisionAnalysisResult("观察屏幕", "看不清", "图像模糊，主体无法辨认");

        String prompt = (String) ReflectionTestUtils.invokeMethod(
                service,
                "buildDesktopGreetingPrompt",
                "你是一个可爱的桌面宠物。",
                "某个游戏窗口",
                analysis);

        assertThat(prompt)
                .contains("你是一个可爱的桌面宠物。")
                .contains("某个游戏窗口")
                .contains("图像可辨识度：看不清")
                .contains("自然承认看不太清")
                .contains("不超过40字");
    }
}
