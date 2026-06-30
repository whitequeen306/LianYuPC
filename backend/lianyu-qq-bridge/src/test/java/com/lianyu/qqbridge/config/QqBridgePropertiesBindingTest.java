package com.lianyu.qqbridge.config;

import com.lianyu.qqbridge.bridge.QqBridgeTurnHandler;
import com.lianyu.qqbridge.napcat.NapCatClient;
import com.lianyu.service.conversation.ConversationService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 装配 / 绑定安全测试，守护「默认 enabled=false → 对主应用零影响」这条核心护栏。
 * <p>
 * 关键点：{@link QqBridgeProperties} 本身是无条件的 @ConfigurationProperties（非 @QqBridgeComponent），
 * 即便桥关闭也会被绑定。因此必须验证：关闭状态下绑定空值（尤其 {@code allow-groups=""}）不会
 * 引发 Long 解析异常导致启动崩——这正是「生产不开就零影响」的底线。
 * <p>
 * 三条断言：
 * 1. 不设任何 qq-bridge 属性（默认）→ 上下文正常启动，活跃 Bean（NapCatClient/QqBridgeTurnHandler）一个不装配；
 * 2. enabled=false 且 allow-groups 为空串 → 上下文不崩，allowGroups 绑定为空 List；
 * 3. enabled=true + binding → 桥 Bean 装配，allow-groups="111,222" 正确绑定为 [111,222]。
 * <p>
 * 用轻量 {@link ApplicationContextRunner}（无 Web 容器、无 DB）；NapCatClient 的 @EventListener(ApplicationReadyEvent)
 * 在此不会触发（非完整 boot），故不会尝试连 WS。
 */
class QqBridgePropertiesBindingTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(BridgeConfig.class, NapCatClient.class, QqBridgeTurnHandler.class);

    @Test
    void noPropertiesSet_contextLoadsNoBridgeBeans() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(NapCatClient.class);
            assertThat(context).doesNotHaveBean(QqBridgeTurnHandler.class);

            QqBridgeProperties props = context.getBean(QqBridgeProperties.class);
            assertThat(props.isEnabled()).isFalse();
            assertThat(props.getBinding().getQqUserId()).isZero();
            assertThat(props.getBinding().getLianyuUserId()).isZero();
            assertThat(props.getBinding().getAllowGroups()).isEmpty();
        });
    }

    @Test
    void disabledWithEmptyAllowGroups_bindsSafely_noBridgeBeans() {
        runner.withPropertyValues(
                "lianyu.qq-bridge.enabled=false",
                "lianyu.qq-bridge.binding.allow-groups=")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(NapCatClient.class);
                    assertThat(context).doesNotHaveBean(QqBridgeTurnHandler.class);

                    QqBridgeProperties props = context.getBean(QqBridgeProperties.class);
                    // 空串必须绑成空 List，而非 ["" ] 触发 NumberFormatException
                    assertThat(props.getBinding().getAllowGroups()).isEmpty();
                });
    }

    @Test
    void enabledWithBinding_bridgeBeansPresent_allowGroupsParsed() {
        runner.withPropertyValues(
                "lianyu.qq-bridge.enabled=true",
                "lianyu.qq-bridge.napcat.ws-url=ws://127.0.0.1:39999",
                "lianyu.qq-bridge.binding.qq-user-id=10001",
                "lianyu.qq-bridge.binding.lianyu-user-id=1",
                "lianyu.qq-bridge.binding.conversation-id=42",
                "lianyu.qq-bridge.binding.allow-groups=111,222")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(NapCatClient.class);
                    assertThat(context).hasSingleBean(QqBridgeTurnHandler.class);

                    QqBridgeProperties props = context.getBean(QqBridgeProperties.class);
                    assertThat(props.isEnabled()).isTrue();
                    assertThat(props.getBinding().getQqUserId()).isEqualTo(10001L);
                    assertThat(props.getBinding().getLianyuUserId()).isEqualTo(1L);
                    assertThat(props.getBinding().getConversationId()).isEqualTo(42L);
                    assertThat(props.getBinding().getAllowGroups()).containsExactly(111L, 222L);
                });
    }

    /** 注册 QqBridgeProperties 绑定，并提供一个 mock ConversationService 供 QqBridgeTurnHandler 构造。 */
    @Configuration
    @EnableConfigurationProperties(QqBridgeProperties.class)
    static class BridgeConfig {
        @Bean
        ConversationService conversationService() {
            return Mockito.mock(ConversationService.class);
        }
    }
}
