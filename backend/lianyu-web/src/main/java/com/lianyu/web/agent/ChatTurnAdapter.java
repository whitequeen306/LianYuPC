package com.lianyu.web.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.lianyu.service.agent.AgentStreamCallback;
import com.lianyu.service.agent.ChatTurnOutcome;
import com.lianyu.service.agent.ChatTurnPort;
import com.lianyu.service.agent.ChatTurnRequest;
import com.lianyu.service.ai.ApiKeyVaultService;
import com.lianyu.service.dto.MessageDto;
import com.lianyu.service.dto.VaultEntryResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * {@link ChatTurnPort} 的 web 侧实现：解析 vault → 调 Python 聊天回合端点。
 *
 * <p>**只转发，不组装**：body 里没有任何 prompt 文本或拼好的消息列表，
 * 编排（prompt 组装 → 消息拼装 → 模型调用）全部在 `backend-agent/app/chat_turn.py`
 * 的 LangGraph 里完成。这里仅负责三件 Java 独有的事：
 * <ol>
 *   <li>Jasypt 解密用户 vault（主密钥只在 Java）</li>
 *   <li>把 SSE 流交给 {@link AgentStreamBridge}，与落库回调对接</li>
 *   <li>出站走 {@link AgentRuntimeClient}（SSRF pinning + 显式超时）</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatTurnAdapter implements ChatTurnPort {

    private static final long SSE_TIMEOUT_MS = 1_800_000L;
    private static final String INSPECT_URI = "/agent/chat-turn/inspect";
    private static final String STREAM_URI = "/agent/chat-turn/stream";

    private final AgentRuntimeClient client;
    private final AgentStreamBridge bridge;
    private final ApiKeyVaultService vaultService;

    @Override
    public ChatTurnOutcome assemble(ChatTurnRequest request) {
        return call(request, false);
    }

    @Override
    public ChatTurnOutcome invoke(ChatTurnRequest request) {
        return call(request, true);
    }

    @Override
    public SseEmitter stream(ChatTurnRequest request, AgentStreamCallback callback) {
        if (!reachable()) {
            log.warn("Chat turn stream skipped: agent unreachable, userId={}", request.getUserId());
            return null;
        }
        Credentials credentials = resolveCredentials(request);
        if (credentials == null) {
            return null;
        }
        Map<String, Object> body = toBody(request, credentials, true);
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        AgentStreamBridge.Relay relay = bridge.newRelay(emitter, callback);
        log.info("Chat turn stream dispatched: userId={}, scene={}, messages={}, model={}",
                request.getUserId(), request.getScene(),
                request.getHistory() == null ? 0 : request.getHistory().size(),
                credentials.model());
        client.postStream(STREAM_URI, request.getUserId(), body)
                .subscribe(
                        relay::accept,
                        error -> {
                            log.warn("Chat turn stream upstream error: {}", error.toString());
                            relay.fail("消息发送失败，请稍后再试");
                        },
                        relay::finish);
        return emitter;
    }

    // ── 非流式（组装 / 阻塞调用） ──

    private ChatTurnOutcome call(ChatTurnRequest request, boolean invoke) {
        if (!reachable()) {
            return null;
        }
        Credentials credentials = resolveCredentials(request);
        if (credentials == null) {
            return null;
        }
        String raw = client.postJson(INSPECT_URI, request.getUserId(),
                toBody(request, credentials, invoke));
        if (raw == null) {
            return null;
        }
        JsonNode root = client.readTree(raw);
        if (root.hasNonNull("error")) {
            log.warn("Chat turn {} failed: userId={}, scene={}, error={}",
                    invoke ? "invoke" : "assemble", request.getUserId(), request.getScene(),
                    root.path("error").asText());
            return null;
        }
        List<MessageDto> messages = new ArrayList<>();
        for (JsonNode node : root.path("messages")) {
            MessageDto dto = new MessageDto();
            dto.setRole(node.path("role").isMissingNode() ? null : node.path("role").asText(null));
            dto.setContent(node.path("content").isMissingNode() ? null : node.path("content").asText(null));
            messages.add(dto);
        }
        return new ChatTurnOutcome(
                root.path("systemPrompt").asText(""),
                messages,
                root.path("content").asText(""),
                intOrNull(root.path("promptTokens")),
                intOrNull(root.path("completionTokens")),
                intOrNull(root.path("totalTokens")));
    }

    private static Integer intOrNull(JsonNode node) {
        return node.isMissingNode() || node.isNull() ? null : node.asInt();
    }

    // ── 凭据与请求体 ──

    private record Credentials(String baseUrl, String apiKey, String model) {
    }

    private Credentials resolveCredentials(ChatTurnRequest request) {
        String baseUrl;
        String apiKey;
        String model;
        try {
            VaultEntryResponse vault = vaultService.resolveForChat(request.getUserId(), request.getProvider());
            if (vault == null) {
                log.warn("Chat turn skipped: no text model for userId={}, provider={}",
                        request.getUserId(), request.getProvider());
                return null;
            }
            baseUrl = vault.getBaseUrl();
            apiKey = vault.getId() != null
                    ? vaultService.decryptKeyForChat(vault.getId())
                    : vault.getApiKey();
            model = request.getModel() != null && !request.getModel().isBlank()
                    ? request.getModel().trim()
                    : vault.getModelDefault();
        } catch (Exception e) {
            log.warn("Chat turn: vault resolve failed: {}", e.toString());
            return null;
        }
        if (baseUrl == null || baseUrl.isBlank() || apiKey == null || apiKey.isBlank()) {
            return null;
        }
        return new Credentials(baseUrl, apiKey, model);
    }

    private Map<String, Object> toBody(ChatTurnRequest request, Credentials credentials, boolean invoke) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userId", request.getUserId());
        body.put("conversationId", request.getConversationId());
        body.put("characterId", request.getCharacterId());
        body.put("characterName", nullToEmpty(request.getCharacterName()));
        body.put("settings", request.getSettings() == null ? Map.of() : request.getSettings());
        body.put("promptTemplate", request.getPromptTemplate());
        body.put("scene", request.getScene() == null ? "SINGLE" : request.getScene());
        body.put("outputLanguage", request.getOutputLanguage());
        body.put("userText", nullToEmpty(request.getUserText()));
        body.put("rawUserText", request.getRawUserText());
        body.put("history", request.getHistory() == null ? List.of() : request.getHistory());
        body.put("preparedMessages",
                request.getPreparedMessages() == null ? List.of() : request.getPreparedMessages());
        body.put("currentUserMessageId", request.getCurrentUserMessageId());
        body.put("timeFact", request.getTimeFact());
        body.put("toolFlags", request.getToolFlags());
        body.put("enableChatTools", request.isEnableChatTools());
        body.put("showInnerThoughts", request.isShowInnerThoughts());
        body.put("maxPieces", request.getMaxPieces());
        body.put("groupMaxPieces", request.getGroupMaxPieces());
        body.put("groupOtherCharacters", nullToEmpty(request.getGroupOtherCharacters()));
        body.put("groupMention", nullToEmpty(request.getGroupMention()));
        body.put("extraSystemSuffix", nullToEmpty(request.getExtraSystemSuffix()));
        body.put("prefetchRealWorld", request.isPrefetchRealWorld());
        body.put("expectedLanguage", request.getExpectedLanguage());
        body.put("provider", request.getProvider());
        body.put("baseUrl", credentials.baseUrl());
        body.put("apiKey", credentials.apiKey());
        body.put("model", credentials.model());
        body.put("temperature", request.getTemperature() == null ? 0.8 : request.getTemperature());
        body.put("maxTokens", request.getMaxTokens() == null ? 1024 : request.getMaxTokens());
        body.put("invoke", invoke);
        return body;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    // ── 健康探测（3 秒缓存，与 AgentStreamAdapter 一致） ──

    private volatile boolean healthy;
    private volatile long healthyCheckedAt;

    private boolean reachable() {
        long now = System.currentTimeMillis();
        if (now - healthyCheckedAt < 3_000L) {
            return healthy;
        }
        healthy = client.isHealthy();
        healthyCheckedAt = now;
        return healthy;
    }
}
