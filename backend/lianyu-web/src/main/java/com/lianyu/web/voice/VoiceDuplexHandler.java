package com.lianyu.web.voice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lianyu.common.exception.BusinessException;
import com.lianyu.service.ai.AsrService;
import com.lianyu.service.ai.AsrStreamClient;
import com.lianyu.service.voice.VoiceSynthesisService;
import com.lianyu.service.auth.AuthRateLimiter;
import com.lianyu.service.conversation.VoiceCallService;
import com.lianyu.service.voice.VoiceCallDuplexSession;
import com.lianyu.service.voice.VoiceCallOptions;
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
    private final VoiceSynthesisService voiceSynthesisService;
    private final AuthRateLimiter authRateLimiter;

    @Value("${lianyu.voice.duplex.enabled:true}")
    private boolean duplexEnabled;

    /** P1 开关：默认关，部署 .env 验收后逐项打开（AGENTS §3.4）。 */
    @Value("${lianyu.voice-call.binary-audio.enabled:false}")
    private boolean binaryAudioEnabled;

    @Value("${lianyu.voice-call.clip.enabled:false}")
    private boolean clipEnabled;

    @Value("${lianyu.voice-call.opening.enabled:false}")
    private boolean openingEnabled;

    private final Map<String, SessionState> states = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        states.put(session.getId(), new SessionState());
        if (session.getAttributes().get("userId") != null) {
            // 旧客户端：握手 query 已带 token 并在拦截器完成认证
            markAuthenticated(session, states.get(session.getId()));
        } else if (!duplexEnabled) {
            closeQuietly(session, CloseStatus.NOT_ACCEPTABLE);
        }
        // 其余情况：等待首帧 {"type":"auth","token":...}；未认证前不发 ready、不受理任何业务帧
    }

    private void markAuthenticated(WebSocketSession session, SessionState state) {
        Long userId = (Long) session.getAttributes().get("userId");
        state.userId = userId == null ? 0L : userId;
        state.authenticated = true;
        if (state.userId <= 0 || !duplexEnabled) {
            closeQuietly(session, CloseStatus.NOT_ACCEPTABLE);
            states.remove(session.getId());
            return;
        }
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
            if (!state.authenticated) {
                if ("auth".equals(type)) {
                    String token = root.path("token").asText("");
                    if (com.lianyu.web.config.VoiceWebSocketConfig.bindToken(token, session.getAttributes())) {
                        markAuthenticated(session, state);
                    } else {
                        log.warn("Voice WS first-frame auth failed");
                        send(session, error("UNAUTHORIZED", "语音连接认证失败"));
                        closeQuietly(session, CloseStatus.NOT_ACCEPTABLE);
                        states.remove(session.getId());
                    }
                } else {
                    // 未认证连接的第一个非 auth 帧：直接拒绝，避免泄露任何业务信号
                    log.warn("Voice WS frame before auth, dropping");
                    closeQuietly(session, CloseStatus.POLICY_VIOLATION);
                    states.remove(session.getId());
                }
                return;
            }
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
                case "vad" -> {
                    // 客户端 VAD 边沿（start/end）：服务端状态机据此进入 USER_SPEAKING，
                    // 思考期插话与之后的冷场计时都依赖它
                    if (state.call != null) {
                        state.call.onVad(root.path("phase").asText(""));
                    }
                }
                case "play.drained" -> {
                    // 客户端播放队列排空：SPEAKING → LISTENING，麦克风重新放行
                    if (state.call != null) {
                        state.call.onPlayDrained(root.path("seq").asLong(0));
                    }
                }
                case "call.metrics" -> {
                    if (state.call != null) {
                        state.call.onClientMetrics(root);
                    }
                }
                case "barge_in" -> {
                    if (state.call != null) {
                        state.call.bargeIn(root.path("preRollMs").asInt(0));
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
        if (state == null || !state.authenticated) {
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
            public boolean sendBinary(byte[] payload) {
                if (!session.isOpen()) {
                    // 已关闭：当作已处理，避免退回 base64 再失败一次
                    return true;
                }
                try {
                    synchronized (session) {
                        session.sendMessage(new BinaryMessage(payload));
                    }
                    return true;
                } catch (IOException e) {
                    log.debug("voice ws binary send failed: {}", e.toString());
                    return false;
                }
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
            // 客户端能力声明：老客户端缺 vad/drained 时靠状态机空端点回收与字节估算兜底，
            // 只有 binary.audio 需要双向协商（配置开 + 客户端声明才切二进制帧）。
            String clientCaps = capsOf(root);
            boolean binaryAudio = binaryAudioEnabled && hasCap(root, "binary.audio");
            VoiceCallOptions options = new VoiceCallOptions(binaryAudio, clipEnabled, openingEnabled);
            authRateLimiter.checkRateLimit("rate:voice-call:", String.valueOf(state.userId),
                    120, Duration.ofMinutes(1), "语音通话过于频繁，请稍后再试");
            state.call = new VoiceCallDuplexSession(
                    sink, objectMapper, state.userId, conversationId,
                    voiceCallService, asrStreamClient, voiceSynthesisService, options);
            var target = state.call.getTarget();
            send(session, event("session.started", n -> {
                n.put("mode", "call");
                n.put("conversationId", conversationId);
                // 协议版本 + 服务端能力：客户端据此决定是否镜像 call.state / 走 barge pre-roll /
                // 播放 tts.clip / 按二进制帧收音频。不认识的客户端会忽略这些附加字段。
                n.put("protocol", 1);
                var caps = n.putArray("caps");
                caps.add("call.state");
                caps.add("barge.preroll");
                if (clipEnabled) {
                    caps.add("clip");
                }
                if (binaryAudio) {
                    caps.add("binary.audio");
                }
                if (options.openingEnabled()) {
                    caps.add("opening");
                }
                n.put("ttsMode", target.isLocal() ? "local_sovits" : "realtime");
                if (target.isLocal()) {
                    n.put("endpoint", target.endpoint() == null ? "" : target.endpoint());
                    n.put("refAudioUrl", target.refAudioUrl() == null ? "" : target.refAudioUrl());
                    n.put("refText", target.refText() == null ? "" : target.refText());
                }
            }));
            // 会话建立即下发一次全量状态（seq=0），客户端不必等第一次迁移才有镜像
            state.call.announceState();
            // P1 开场：pickup clip + 异步 cue；开关默认关，用户先开口会自动取消
            state.call.startOpening();
            log.info("Voice duplex call started user={} conv={} clientCaps={} opening={} clip={} binary={}",
                    state.userId, conversationId, clientCaps,
                    options.openingEnabled(), options.clipEnabled(), options.binaryAudio());
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

    /** 客户端声明的能力列表（数组形态优先，其次逗号串），只用于日志。 */
    private static String capsOf(JsonNode root) {
        JsonNode caps = root.path("caps");
        if (caps.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode c : caps) {
                if (sb.length() > 0) {
                    sb.append(',');
                }
                sb.append(c.asText(""));
            }
            return sb.toString();
        }
        return caps.isTextual() ? caps.asText("") : "";
    }

    /** 客户端是否声明了某个能力（数组或逗号串）。 */
    private static boolean hasCap(JsonNode root, String cap) {
        JsonNode caps = root.path("caps");
        if (caps.isArray()) {
            for (JsonNode c : caps) {
                if (cap.equals(c.asText(""))) {
                    return true;
                }
            }
            return false;
        }
        if (caps.isTextual()) {
            for (String item : caps.asText("").split(",")) {
                if (cap.equals(item.trim())) {
                    return true;
                }
            }
        }
        return false;
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
        long userId;
        boolean authenticated;
        VoiceDictationSession dictation;
        VoiceCallDuplexSession call;

        SessionState() {
        }
    }
}
