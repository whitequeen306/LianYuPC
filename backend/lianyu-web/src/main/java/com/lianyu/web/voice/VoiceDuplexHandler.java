package com.lianyu.web.voice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lianyu.common.exception.BusinessException;
import com.lianyu.service.ai.AsrService;
import com.lianyu.service.ai.AsrStreamClient;
import com.lianyu.service.ai.DashScopeTtsRealtimeService;
import com.lianyu.service.auth.AuthRateLimiter;
import com.lianyu.service.conversation.VoiceCallService;
import com.lianyu.service.voice.VoiceCallDuplexSession;
import com.lianyu.service.voice.VoiceDictationSession;
import com.lianyu.service.voice.VoiceEventSink;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

@Slf4j
@Component
@RequiredArgsConstructor
public class VoiceDuplexHandler extends AbstractWebSocketHandler {

    private final ObjectMapper objectMapper;
    private final AsrService asrService;
    private final AsrStreamClient asrStreamClient;
    private final VoiceCallService voiceCallService;
    private final DashScopeTtsRealtimeService ttsRealtimeService;
    private final AuthRateLimiter authRateLimiter;

    @Value("${lianyu.voice.duplex.enabled:true}")
    private boolean duplexEnabled;

    private final Map<String, SessionState> states = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        Long userId = (Long) session.getAttributes().get("userId");
        if (userId == null || !duplexEnabled) {
            closeQuietly(session, CloseStatus.NOT_ACCEPTABLE);
            return;
        }
        states.put(session.getId(), new SessionState(userId));
        send(session, event("ready", n -> n.put("duplex", true)));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        SessionState state = states.get(session.getId());
        if (state == null) {
            return;
        }
        try {
            JsonNode root = objectMapper.readTree(message.getPayload());
            String type = root.path("type").asText("");
            switch (type) {
                case "session.start" -> startSession(session, state, root);
                case "session.end" -> endSession(state);
                case "endpoint" -> {
                    if (state.dictation != null) {
                        state.dictation.clientEndpoint();
                    } else if (state.call != null) {
                        state.call.clientEndpoint();
                    }
                }
                case "barge_in" -> {
                    if (state.call != null) {
                        state.call.bargeIn();
                    }
                }
                case "ping" -> send(session, event("pong", n -> {
                }));
                default -> {
                    // ignore
                }
            }
        } catch (BusinessException e) {
            send(session, error("FORBIDDEN", e.getMessage() == null ? "请求被拒绝" : e.getMessage()));
        } catch (Exception e) {
            log.warn("Voice duplex text handler error: {}", e.toString());
            send(session, error("BAD_REQUEST", "无效的语音会话请求"));
        }
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        SessionState state = states.get(session.getId());
        if (state == null) {
            return;
        }
        ByteBuffer payload = message.getPayload();
        byte[] pcm = new byte[payload.remaining()];
        payload.get(pcm);
        if (state.dictation != null) {
            state.dictation.onPcm(pcm);
        } else if (state.call != null) {
            state.call.onPcm(pcm);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        SessionState state = states.remove(session.getId());
        if (state != null) {
            endSession(state);
        }
    }

    private void startSession(WebSocketSession session, SessionState state, JsonNode root) {
        endSession(state);
        String mode = root.path("mode").asText("");
        VoiceEventSink sink = new VoiceEventSink() {
            @Override
            public void sendText(String json) {
                send(session, json);
            }

            @Override
            public boolean isOpen() {
                return session.isOpen();
            }
        };

        if ("dictation".equals(mode)) {
            authRateLimiter.checkRateLimit("rate:voice-dictation:", String.valueOf(state.userId),
                    60, Duration.ofMinutes(1), "听写过于频繁，请稍后再试");
            state.dictation = new VoiceDictationSession(sink, objectMapper, asrService, asrStreamClient);
            send(session, event("session.started", n -> n.put("mode", "dictation")));
            return;
        }
        if ("call".equals(mode)) {
            long conversationId = root.path("conversationId").asLong(0);
            if (conversationId <= 0) {
                send(session, error("BAD_REQUEST", "缺少 conversationId"));
                return;
            }
            authRateLimiter.checkRateLimit("rate:voice-call:", String.valueOf(state.userId),
                    120, Duration.ofMinutes(1), "语音通话过于频繁，请稍后再试");
            state.call = new VoiceCallDuplexSession(
                    sink, objectMapper, state.userId, conversationId,
                    voiceCallService, asrStreamClient, ttsRealtimeService);
            send(session, event("session.started", n -> {
                n.put("mode", "call");
                n.put("conversationId", conversationId);
            }));
            return;
        }
        send(session, error("BAD_REQUEST", "未知 mode"));
    }

    private void endSession(SessionState state) {
        if (state.dictation != null) {
            state.dictation.close();
            state.dictation = null;
        }
        if (state.call != null) {
            state.call.close();
            state.call = null;
        }
    }

    private String event(String type, java.util.function.Consumer<ObjectNode> filler) {
        try {
            ObjectNode n = objectMapper.createObjectNode();
            n.put("type", type);
            if (filler != null) {
                filler.accept(n);
            }
            return objectMapper.writeValueAsString(n);
        } catch (Exception e) {
            return "{\"type\":\"error\",\"message\":\"encode failed\"}";
        }
    }

    private String error(String code, String message) {
        return event("error", n -> {
            n.put("code", code);
            n.put("message", message);
        });
    }

    private void send(WebSocketSession session, String json) {
        if (!session.isOpen()) {
            return;
        }
        try {
            synchronized (session) {
                session.sendMessage(new TextMessage(json));
            }
        } catch (IOException e) {
            log.debug("voice ws send failed: {}", e.toString());
        }
    }

    private static void closeQuietly(WebSocketSession session, CloseStatus status) {
        try {
            session.close(status);
        } catch (IOException ignored) {
            // ignore
        }
    }

    private static final class SessionState {
        final long userId;
        VoiceDictationSession dictation;
        VoiceCallDuplexSession call;

        SessionState(long userId) {
            this.userId = userId;
        }
    }
}
