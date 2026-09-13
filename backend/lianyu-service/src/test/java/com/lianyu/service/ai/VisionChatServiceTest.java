package com.lianyu.service.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.lianyu.service.dto.MessageDto;
import com.lianyu.service.storage.FileStorageService;
import com.lianyu.service.user.UserPublicProfileService;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.Message;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class VisionChatServiceTest {

    @Mock private AiChatService aiChatService;
    @Mock private ApiKeyVaultService vaultService;
    @Mock private FileStorageService fileStorageService;
    @Mock private UserPublicProfileService userPublicProfileService;
    @Mock private ScheduledExecutorService scheduler;
    @Mock private Executor aiStreamExecutor;

    private VisionChatService service;

    @BeforeEach
    void setUp() {
        io.github.resilience4j.bulkhead.BulkheadRegistry bulkheads =
                io.github.resilience4j.bulkhead.BulkheadRegistry.ofDefaults();
        AiResilience resilience = new AiResilience(
                bulkheads,
                io.github.resilience4j.timelimiter.TimeLimiterRegistry.ofDefaults(),
                io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry.ofDefaults(),
                scheduler,
                aiStreamExecutor,
                aiStreamExecutor);
        SseChatStreamHelper sseHelper = new SseChatStreamHelper(new com.fasterxml.jackson.databind.ObjectMapper());
        VisionMessageBuilder visionMessageBuilder = new VisionMessageBuilder(fileStorageService);
        VisionAnalysisParser visionAnalysisParser =
                new VisionAnalysisParser(new com.fasterxml.jackson.databind.ObjectMapper());
        service = new VisionChatService(
                aiChatService,
                resilience,
                sseHelper,
                visionMessageBuilder,
                visionAnalysisParser,
                vaultService,
                userPublicProfileService);
    }

    @Test
    void buildImageAugmentedTextMessageDtos_appendsStructuredVisionContext() {
        MessageDto system = new MessageDto();
        system.setRole("system");
        system.setContent("你是一个温柔角色。");

        MessageDto user = new MessageDto();
        user.setRole("user");
        user.setContent("帮我看看这张图");
        user.setImageUrl("/api/public/files/chat-images/demo.png");

        VisionAnalysisResult analysis = new VisionAnalysisResult("求识图", "high", "一只橘猫趴在窗台上");

        @SuppressWarnings("unchecked")
        List<MessageDto> dtos = (List<MessageDto>) ReflectionTestUtils.invokeMethod(
                service,
                "buildImageAugmentedTextMessageDtos",
                List.of(system, user),
                analysis);
        @SuppressWarnings("unchecked")
        List<Message> messages = (List<Message>) ReflectionTestUtils.invokeMethod(
                service, "toTextOnlySpringMessages", dtos);

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
    void buildImageAugmentedTextMessageDtos_imageOnlyEmptyXml_injectsDescriptionIntoUserTurn() {
        MessageDto system = new MessageDto();
        system.setRole("system");
        system.setContent("你是江之岛盾子。");

        MessageDto user = new MessageDto();
        user.setRole("user");
        user.setContent("<user_message trusted=\"false\"></user_message>");
        user.setImageUrl("/api/public/files/chat-images/demo.png");

        VisionAnalysisResult analysis = new VisionAnalysisResult(
                "分享日常", "high", "夜间户外餐桌上有烤肉、西瓜和几个人");

        @SuppressWarnings("unchecked")
        List<MessageDto> dtos = (List<MessageDto>) ReflectionTestUtils.invokeMethod(
                service,
                "buildImageAugmentedTextMessageDtos",
                List.of(system, user),
                analysis);
        @SuppressWarnings("unchecked")
        List<Message> messages = (List<Message>) ReflectionTestUtils.invokeMethod(
                service, "toTextOnlySpringMessages", dtos);

        assertThat(messages).hasSize(2);
        assertThat(messages.get(0).getText())
                .contains("禁止当成空消息");
        assertThat(messages.get(1).getText())
                .contains("我发了一张图片")
                .contains("夜间户外餐桌上有烤肉、西瓜和几个人")
                .contains("不要当成空消息");
    }

    @Test
    void buildImageAugmentedTextMessageDtos_whenLowConfidence_addsHonestVisibilityRule() {
        MessageDto user = new MessageDto();
        user.setRole("user");
        user.setContent("这是什么");
        user.setImageUrl("/api/public/files/chat-images/demo.png");

        VisionAnalysisResult analysis = new VisionAnalysisResult("求识图", "看不清", "图像模糊，主体无法辨认");

        @SuppressWarnings("unchecked")
        List<MessageDto> dtos = (List<MessageDto>) ReflectionTestUtils.invokeMethod(
                service,
                "buildImageAugmentedTextMessageDtos",
                List.of(user),
                analysis);
        @SuppressWarnings("unchecked")
        List<Message> messages = (List<Message>) ReflectionTestUtils.invokeMethod(
                service, "toTextOnlySpringMessages", dtos);

        assertThat(messages).hasSize(2);
        assertThat(messages.get(0).getText())
                .contains("可辨识度: 看不清")
                .contains("必须如实告诉用户这张图看不太清");
    }
}
