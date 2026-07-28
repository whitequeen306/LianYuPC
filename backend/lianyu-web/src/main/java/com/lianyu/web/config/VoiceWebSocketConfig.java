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
                        String query = request.getURI().getQuery();
                        String token = extractToken(query);
                        if (token == null || token.isBlank()) {
                            log.warn("Voice WS rejected: missing token");
                            return false;
                        }
                        try {
                            Object loginId = StpUtil.getLoginIdByToken(token);
                            if (loginId == null) {
                                log.warn("Voice WS rejected: invalid token");
                                return false;
                            }
                            attributes.put("userId", Long.parseLong(loginId.toString()));
                            return true;
                        } catch (Exception e) {
                            log.warn("Voice WS rejected: {}", e.getMessage());
                            return false;
                        }
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
