package com.lianyu.web.agent;

import com.lianyu.service.agent.PromptAssemblyPort;
import com.lianyu.service.agent.PromptAssemblyRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * AGENT 模式下由 Python 组装 system prompt。
 *
 * <p>失败语义：任何异常/错误响应/空结果都返回 {@code null}，调用方回退 Java 组装。
 * 这条路径是**用户可见**的，所以宁可回退也不能抛。
 *
 * <p>模式判断放在这里而不是节点里：节点只关心「有没有拿到 prompt」。
 */
@Slf4j
@Component
public class AgentPromptAssemblyAdapter implements PromptAssemblyPort {

    private final AgentRuntimeClient client;

    public AgentPromptAssemblyAdapter(AgentRuntimeClient client) {
        this.client = client;
    }

    @Override
    public String assembleIfEnabled(PromptAssemblyRequest request) {
        if (request == null) {
            return null;
        }
        if (!reachable()) {
            return null;
        }
        try {
            String response = client.postJson("/agent/prompt/assemble",
                    request.userId(), bodyOf(request));
            if (response == null) {
                log.debug("Agent prompt assembly unavailable, fallback to Java");
                return null;
            }
            com.fasterxml.jackson.databind.JsonNode root = client.readTree(response);
            if (root.hasNonNull("error")) {
                log.warn("Agent prompt assembly error {} — fallback to Java",
                        root.path("error").asText());
                return null;
            }
            String prompt = root.path("systemPrompt").asText("");
            if (prompt.isBlank()) {
                log.warn("Agent prompt assembly returned blank — fallback to Java");
                return null;
            }
            log.debug("Agent prompt assembly ok: chars={}", prompt.length());
            return prompt;
        } catch (Exception e) {
            log.warn("Agent prompt assembly failed ({}), fallback to Java", e.toString());
            return null;
        }
    }

    static Map<String, Object> bodyOf(PromptAssemblyRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userId", request.userId());
        body.put("characterName", request.characterName() == null ? "" : request.characterName());
        body.put("settings", request.settings() == null ? Map.of() : request.settings());
        body.put("promptTemplate", request.promptTemplate());
        body.put("memoryContext", "");
        // 文案由 Python 生成：这里只传开关事实（含桌面桥接是否有 computer_task）
        body.put("toolHint", "");
        body.put("toolFlags", request.toolFlags());
        body.put("emotionBlock", "");
        body.put("outputLanguage", request.outputLanguage() == null ? "zh" : request.outputLanguage());
        body.put("enableChatTools", request.enableChatTools());
        body.put("showInnerThoughts", request.showInnerThoughts());
        body.put("maxPieces", request.maxPieces());
        body.put("includeTime", request.includeTime());
        body.put("timeFact", request.timeFact());
        body.put("city", null);
        body.put("afterBase", request.afterBase() == null ? "full" : request.afterBase());
        // 让 Python 自己读记忆/关系/情绪/摘要
        body.put("useInternalData", true);
        body.put("scene", request.scene());
        body.put("lastUserMessage", request.lastUserMessage());
        body.put("extraSystemSuffix", request.extraSystemSuffix());
        body.put("groupMaxPieces", request.groupMaxPieces());
        body.put("groupOtherCharacters", request.groupOtherCharacters());
        body.put("groupMention", request.groupMention());
        if (request.characterId() != null) {
            body.put("characterId", request.characterId());
        }
        if (request.conversationId() != null) {
            body.put("conversationId", request.conversationId());
        }
        return body;
    }
    // ── 健康探测（3 秒缓存）：agent 不可用时直接回退，避免每条消息都等超时 ──

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
    }}
