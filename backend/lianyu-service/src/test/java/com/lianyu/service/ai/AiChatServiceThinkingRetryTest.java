package com.lianyu.service.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lianyu.service.dto.AiChatRequest;
import com.lianyu.service.dto.MessageDto;
import com.lianyu.service.dto.VaultEntryResponse;
import com.lianyu.service.rules.PromptRuleEngine;
import com.lianyu.service.storage.FileStorageService;
import com.lianyu.service.support.OutputLanguageService;
import com.lianyu.service.tools.ToolManager;
import com.lianyu.service.user.UserPublicProfileService;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class AiChatServiceThinkingRetryTest {

    @Mock private ApiKeyVaultService vaultService;
    @Mock private FileStorageService fileStorageService;
    @Mock private ToolManager toolManager;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ScheduledExecutorService scheduler;
    @Mock private Executor aiStreamExecutor;
    @Mock private PromptRuleEngine promptRuleEngine;
    @Mock private OutputLanguageService outputLanguageService;
    @Mock private UserPublicProfileService userPublicProfileService;

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
                aiStreamExecutor,
                promptRuleEngine,
                outputLanguageService,
                userPublicProfileService);
    }

    @Test
    void thinkingDisabledExtraBody_setsDeepSeekChatCompletionsToggle() {
        Map<String, Object> extra = AiChatService.thinkingDisabledExtraBody();
        assertThat(extra.get("thinking")).isEqualTo(Map.of("type", "disabled"));
    }

    @Test
    void buildPrompt_defaultKeepsThinkingOnAndAttachesTools() {
        stubTools();
        OpenAiChatOptions options = optionsFrom(buildPrompt(false));
        assertThat(options.getExtraBody()).isNull();
        assertThat(options.getToolCallbacks()).isNotEmpty();
        assertThat(options.getInternalToolExecutionEnabled()).isTrue();
    }

    @Test
    void buildPrompt_thinkingDisabledRetryKeepsTools() {
        stubTools();
        OpenAiChatOptions options = optionsFrom(buildPrompt(true));
        assertThat(options.getExtraBody()).isEqualTo(AiChatService.thinkingDisabledExtraBody());
        assertThat(options.getToolCallbacks()).isNotEmpty();
        assertThat(options.getInternalToolExecutionEnabled()).isTrue();
    }

    private void stubTools() {
        when(toolManager.resolveToolCallbacks(any())).thenReturn(List.of(mock(ToolCallback.class)));
    }

    private Prompt buildPrompt(boolean thinkingDisabled) {
        AiChatRequest request = new AiChatRequest();
        request.setProvider("ko");
        request.setModel("deepseek-v4-pro");
        request.setChatToolCharacterId(620L);
        MessageDto user = new MessageDto();
        user.setRole("user");
        user.setContent("你好");
        request.setMessages(List.of(user));

        VaultEntryResponse vault = VaultEntryResponse.builder()
                .baseUrl("https://api.deepseek.com")
                .provider("ko")
                .modelDefault("deepseek-v4-pro")
                .build();
        List<Message> messages = List.of(new UserMessage("你好"));
        return (Prompt) ReflectionTestUtils.invokeMethod(
                service, "buildPrompt", request, vault, messages, thinkingDisabled);
    }

    private static OpenAiChatOptions optionsFrom(Prompt prompt) {
        return (OpenAiChatOptions) prompt.getOptions();
    }
}
