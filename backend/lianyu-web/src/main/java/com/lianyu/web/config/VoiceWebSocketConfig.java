package com.lianyu.web.config;

import cn.dev33.satoken.stp.StpUtil;
import com.lianyu.web.voice.VoiceDuplexHandler;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.HandshakeInterceptor;

@Slf4j
@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class VoiceWebSocketConfig implements WebSocketConfigurer {

    private final VoiceDuplexHandler voiceDuplexHandler;

    @Value("${ws.allowed-origin-patterns:*}")
    private String allowedOriginPatterns;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(voiceDuplexHandler, "/ws/voice")
                .addInterceptors(new HandshakeInterceptor() {
                    @Override
                    public boolean beforeHandshake(
                            ServerHttpRequest request,
                            ServerHttpResponse response,
                            WebSocketHandler wsHandler,
                            Map<String, Object> attributes) {
                        // 首帧认证模式：握手不带凭据（浏览器原生 WS 无法带自定义 header），
                        // token 由客户端连上后以 {"type":"auth","token":...} 首帧提交，
                        // 认证在 VoiceDuplexHandler 中完成，未认证会话不允许 startSession/二进制上行。
                        // query 传 token 仅作旧客户端兼容回退（会进 nginx access log，将在客户端全面切换后移除）。
                        String query = request.getURI().getQuery();
                        String token = extractToken(query);
                        if (token != null && !token.isBlank()) {
                            return bindToken(token, attributes);
                        }
                        log.info("Voice WS handshake without credential, awaiting first-frame auth");
                        return true;
                    }

                    @Override
                    public void afterHandshake(
                            ServerHttpRequest request,
                            ServerHttpResponse response,
                            WebSocketHandler wsHandler,
                            Exception exception) {
                        // no-op
                    }
                })
                .setAllowedOriginPatterns(allowedOriginPatterns.split(","));
    }

    /** 校验 token 并把 userId 写入会话属性；返回是否认证通过。供 VoiceDuplexHandler 首帧认证复用。 */
    public static boolean bindToken(String token, Map<String, Object> attributes) {
        if (token == null || token.isBlank()) {
            return false;
        }
        try {
            Object loginId = StpUtil.getLoginIdByToken(token);
            if (loginId == null) {
                return false;
            }
            attributes.put("userId", Long.parseLong(loginId.toString()));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static String extractToken(String query) {
        if (query == null || query.isBlank()) {
            return null;
        }
        for (String part : query.split("&")) {
            int eq = part.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = part.substring(0, eq);
            if ("token".equals(key)) {
                return java.net.URLDecoder.decode(part.substring(eq + 1), java.nio.charset.StandardCharsets.UTF_8);
            }
        }
        return null;
    }
}
