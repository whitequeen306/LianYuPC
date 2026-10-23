package com.lianyu.web.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lianyu.service.tools.bridge.AgentBridgeService;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 桌面能力层的内网执行端点：Python（决策方）→ Java（路由/审计）→ STOMP → Electron（执行方）。
 *
 * <p>为什么在 Java 中转而不是让客户端直连 Python：
 * backend-agent 部署上「仅内网、不对外暴露端口」，且双实例经 `agent-lb` 轮询无会话粘性；
 * 而 Java 本来就持有「用户 → 客户端」的 STOMP 会话与心跳。中转是最短路径。
 *
 * <p>安全边界：
 * <ul>
 *   <li>路径 {@code /internal/**} 不经 api-gateway 暴露，也不在 Sa-Token 拦截范围（`/api/**`）内；</li>
 *   <li>认证靠 {@link AgentInternalSigner}（共享密钥 + 时间窗 + 重放缓存），未配置密钥即全拒；</li>
 *   <li>签名覆盖**原始请求体字节**，因此这里收 `String` 自行解析 —— 一旦先反序列化再验签，
 *       签名就失去意义。</li>
 * </ul>
 *
 * <p>红线：本类只搬运与校验，不含任何 prompt/模型调用（`JavaAiLogicRatchetTest` 守护）。
 */
@Slf4j
@RestController
@RequestMapping("/internal/agent/desktop")
@RequiredArgsConstructor
public class InternalDesktopExecController {

    private final AgentBridgeService bridgeService;
    private final AgentInternalSigner signer;
    private final ObjectMapper objectMapper;

    /** Python 下发一次桌面动作。 */
    @PostMapping("/exec")
    public Map<String, Object> exec(
            @RequestHeader(value = "X-Agent-Ts", required = false) String timestamp,
            @RequestHeader(value = "X-Agent-Sig", required = false) String signature,
            @RequestHeader(value = "X-User-Id", required = false) String userHeader,
            @RequestBody(required = false) String rawBody) {

        if (!signer.verify(timestamp, signature, rawBody)) {
            return error("invalid_signature");
        }
        DesktopExecBody body;
        try {
            body = objectMapper.readValue(rawBody, DesktopExecBody.class);
        } catch (Exception e) {
            log.warn("internal exec body unparseable: {}", e.toString());
            return error("bad_request");
        }
        if (body == null || body.userId() == null || body.tool() == null || body.tool().isBlank()) {
            return error("bad_request");
        }
        if (userHeader != null && !userHeader.equals(String.valueOf(body.userId()))) {
            return error("user_mismatch");
        }

        String argsJson;
        try {
            argsJson = objectMapper.writeValueAsString(body.args() == null ? Map.of() : body.args());
        } catch (Exception e) {
            return error("bad_request");
        }
        Map<String, Object> actor = body.actor() == null ? Map.of() : body.actor();

        AgentBridgeService.BridgeResult result = bridgeService.exec(new AgentBridgeService.ExecRequest(
                body.userId(),
                body.tool(),
                argsJson,
                body.timeoutMs(),
                body.danger(),
                body.evidence() == null || body.evidence().isBlank() ? "user_request" : body.evidence(),
                asLong(actor.get("characterId")),
                asString(actor.get("name")),
                asString(actor.get("avatarUrl")),
                body.sessionId() == null ? "" : body.sessionId(),
                body.stepId() == null ? "" : body.stepId()));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", result.ok());
        out.put("text", result.text());
        out.put("data", parseStructured(result.text()));
        out.put("images", result.images());
        out.put("errorCode", result.errorCode());
        out.put("elapsedMs", result.elapsedMs());
        out.put("policy", result.policy());
        return out;
    }

    /** Python 上报一步的现场解说（进度气泡）。失败不影响动作本身。 */
    @PostMapping("/progress")
    public ResponseEntity<Void> progress(
            @RequestHeader(value = "X-Agent-Ts", required = false) String timestamp,
            @RequestHeader(value = "X-Agent-Sig", required = false) String signature,
            @RequestBody(required = false) String rawBody) {

        if (!signer.verify(timestamp, signature, rawBody)) {
            return ResponseEntity.status(401).build();
        }
        try {
            DesktopProgressBody body = objectMapper.readValue(rawBody, DesktopProgressBody.class);
            bridgeService.relayProgress(body.userId(), body.sessionId(), body.stepId(),
                    body.code(), body.text());
        } catch (Exception e) {
            log.debug("internal progress dropped: {}", e.toString());
        }
        return ResponseEntity.noContent().build();
    }

    /** 中止当前动作（Esc / 任务收尾）。 */
    @PostMapping("/abort")
    public ResponseEntity<Void> abort(
            @RequestHeader(value = "X-Agent-Ts", required = false) String timestamp,
            @RequestHeader(value = "X-Agent-Sig", required = false) String signature,
            @RequestBody(required = false) String rawBody) {

        if (!signer.verify(timestamp, signature, rawBody)) {
            return ResponseEntity.status(401).build();
        }
        try {
            DesktopAbortBody body = objectMapper.readValue(rawBody, DesktopAbortBody.class);
            bridgeService.pushCancel(body.userId(), body.stepId());
        } catch (Exception e) {
            log.debug("internal abort dropped: {}", e.toString());
        }
        return ResponseEntity.noContent().build();
    }

    /**
     * 客户端回传的 `content` 若是 JSON 对象，额外解析成 `data` 字段给 Python
     * （`desk_screen_read` 的元素清单、`desk_window_list` 的窗口列表都走这里）。
     */
    private Object parseStructured(String text) {
        if (text == null) {
            return Map.of();
        }
        String trimmed = text.strip();
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(trimmed, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static Map<String, Object> error(String code) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", false);
        out.put("text", "");
        out.put("data", Map.of());
        out.put("images", java.util.List.of());
        out.put("errorCode", code);
        out.put("elapsedMs", 0);
        return out;
    }

    private static Long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Long.parseLong(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /** Python 下发的动作请求体（字段名与 `desktop_channel` 的 payload 一一对应）。 */
    public record DesktopExecBody(Long userId, String sessionId, String stepId, String tool,
                                  Map<String, Object> args, Long timeoutMs, String danger,
                                  String evidence, Map<String, Object> actor) {
    }

    public record DesktopProgressBody(Long userId, String sessionId, String stepId,
                                      String code, String text) {
    }

    public record DesktopAbortBody(Long userId, String sessionId, String stepId) {
    }
}
