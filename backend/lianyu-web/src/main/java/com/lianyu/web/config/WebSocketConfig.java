package com.lianyu.web.config;

import cn.dev33.satoken.stp.StpUtil;
import com.lianyu.common.i18n.OutputLanguage;
import com.lianyu.service.ConversationAccessService;
import com.lianyu.service.OutputLanguageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

import java.security.Principal;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * WebSocket / STOMP 配置。
 * <ul>
 *   <li>CONNECT 帧：必须携带有效 token，否则拒绝。</li>
 *   <li>SUBSCRIBE：验证已认证 + 群聊 topic 会话归属。</li>
 *   <li>SEND 帧：验证已认证 + 限制消息体大小（512 KB）。</li>
 * </ul>
 */
@Slf4j
@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private static final Pattern GROUP_TOPIC = Pattern.compile("^/topic/group/(\\d+)$");

    private final OutputLanguageService outputLanguageService;
    private final ConversationAccessService conversationAccessService;

    /** STOMP 帧最大消息体字节数（512 KB） */
    private static final int MAX_MESSAGE_SIZE = 512 * 1024;

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic", "/queue");
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns("http://localhost:*", "https://localhost:*");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.taskExecutor().corePoolSize(4).maxPoolSize(16);
        registration.interceptors(new ChannelInterceptor() {

            @Override
            public Message<?> preSend(Message<?> message, MessageChannel channel) {
                StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(
                        message, StompHeaderAccessor.class);
                if (accessor == null) {
                    return message;
                }

                if (StompCommand.CONNECT.equals(accessor.getCommand())) {
                    authenticateConnect(accessor);
                }

                if (StompCommand.SUBSCRIBE.equals(accessor.getCommand())
                        || StompCommand.SEND.equals(accessor.getCommand())) {
                    if (accessor.getUser() == null) {
                        log.warn("WebSocket {} rejected: not authenticated", accessor.getCommand());
                        throw new MessageDeliveryException("未认证，请先连接并传递 token");
                    }
                }

                if (StompCommand.SUBSCRIBE.equals(accessor.getCommand())) {
                    assertGroupTopicOwnership(accessor);
                }

                if (StompCommand.SEND.equals(accessor.getCommand())) {
                    int contentLength = accessor.getContentLength();
                    if (contentLength > MAX_MESSAGE_SIZE) {
                        log.warn("WebSocket SEND rejected: payload too large ({} bytes)", contentLength);
                        throw new MessageDeliveryException("消息体过大，最大允许 512 KB");
                    }
                    refreshOutputLanguage(accessor);
                }

                return message;
            }
        });
    }

    private void authenticateConnect(StompHeaderAccessor accessor) {
        String token = accessor.getFirstNativeHeader("token");
        if (token == null || token.isBlank()) {
            log.warn("WebSocket CONNECT rejected: missing token");
            throw new MessageDeliveryException("认证失败：缺少 token，请先登录");
        }
        try {
            Object loginId = StpUtil.getLoginIdByToken(token);
            if (loginId == null) {
                log.warn("WebSocket CONNECT rejected: invalid or expired token");
                throw new MessageDeliveryException("认证失败：token 无效或已过期");
            }
            accessor.setUser(new StompPrincipal(loginId.toString()));

            String outputLang = accessor.getFirstNativeHeader("output-language");
            if (outputLang != null && !outputLang.isBlank()) {
                long userId = Long.parseLong(loginId.toString());
                String normalized = OutputLanguage.fromCode(outputLang).getCode();
                outputLanguageService.cacheForUser(userId, normalized);
                if (accessor.getSessionAttributes() != null) {
                    accessor.getSessionAttributes().put("outputLanguage", normalized);
                }
            }
            log.debug("WebSocket authenticated: userId={}", loginId);
        } catch (MessageDeliveryException mde) {
            throw mde;
        } catch (Exception e) {
            log.warn("WebSocket CONNECT rejected: {}", e.getMessage());
            throw new MessageDeliveryException("认证失败：" + e.getMessage());
        }
    }

    private void assertGroupTopicOwnership(StompHeaderAccessor accessor) {
        String destination = accessor.getDestination();
        if (destination == null || destination.isBlank()) {
            return;
        }
        Matcher matcher = GROUP_TOPIC.matcher(destination.trim());
        if (!matcher.matches()) {
            return;
        }
        long conversationId = Long.parseLong(matcher.group(1));
        long userId = Long.parseLong(accessor.getUser().getName());
        conversationAccessService.assertUserOwnsConversation(userId, conversationId);
        log.debug("WebSocket SUBSCRIBE allowed: userId={}, conversationId={}", userId, conversationId);
    }

    private void refreshOutputLanguage(StompHeaderAccessor accessor) {
        String outputLang = accessor.getFirstNativeHeader("output-language");
        if ((outputLang == null || outputLang.isBlank()) && accessor.getSessionAttributes() != null) {
            Object cached = accessor.getSessionAttributes().get("outputLanguage");
            if (cached instanceof String s && !s.isBlank()) {
                outputLang = s;
            }
        }
        if (outputLang != null && !outputLang.isBlank() && accessor.getUser() != null) {
            try {
                long userId = Long.parseLong(accessor.getUser().getName());
                outputLanguageService.cacheForUser(
                        userId,
                        OutputLanguage.fromCode(outputLang).getCode()
                );
            } catch (Exception ignored) {
                // ignore invalid user id
            }
        }
    }

    record StompPrincipal(String name) implements Principal {
        @Override
        public String getName() {
            return name;
        }
    }
}
