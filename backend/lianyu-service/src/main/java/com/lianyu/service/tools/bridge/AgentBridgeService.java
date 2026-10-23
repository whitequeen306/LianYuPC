package com.lianyu.service.tools.bridge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lianyu.common.base.ErrorCode;
import com.lianyu.common.exception.BusinessException;
import com.lianyu.service.dto.RegisterAgentToolsRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

/**
 * Agent 能力桥：桌面客户端把本地能力清单注册到云端，模型（Python）发起动作时，
 * 经 STOMP 用户队列（{@code /user/queue/agent-tools}）下发到客户端本地执行，
 * 客户端把结果 POST 回 {@code /api/agent-bridge/result} 完成闭环。
 *
 * <p>与「外借引擎」时代的区别：工具从**一个粗粒度黑盒**（{@code computer_task}，单次最长 630s）
 * 变成**一组原子动作**（{@code desk_*}，每步 ≤ step-timeout-ms）。因此这里的核心方法从
 * {@code dispatch}（只看文本）升级为 {@link #exec}（带图片、策略与错误码），
 * 超时改为**按步传入**，并删除了「等引擎重装后重新注册」的 25s 盲等（审计 A5）。
 *
 * <p>会话为内存态（单实例部署）：注册 + 30s 心跳维持，超过 {@link #HEARTBEAT_TTL_MS} 视为离线，
 * 能力自动从模型可见列表消失。
 *
 * <p>红线遵守：{@link #exec} 会阻塞等待客户端结果，只允许在无事务的模型调用线程内使用
 * （与 AI 慢调用同级），绝不允许进入 {@code @Transactional}。
 * 超时/离线返回错误文案而不抛异常，让模型能向用户自然解释失败。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentBridgeService {

    public static final String QUEUE_DESTINATION = "/queue/agent-tools";
    /** 进度旁路：与调用队列分开，避免进度帧挤占动作通道、也便于渲染端只订阅自己关心的 */
    public static final String PROGRESS_DESTINATION = "/queue/agent-tool-progress";

    /** 心跳有效期：客户端每 30s 心跳一次，90s 未见即离线 */
    static final long HEARTBEAT_TTL_MS = 90_000L;
    static final int MAX_TOOLS = 32;
    static final int MAX_NAME_LENGTH = 64;
    static final int MAX_DESCRIPTION_LENGTH = 1024;
    static final int MAX_SCHEMA_LENGTH = 8_192;
    static final int MAX_RESULT_CHARS = 8_000;
    /** 单步最多回传 2 帧截图（一帧看全屏、一帧看局部放大足够） */
    static final int MAX_IMAGES = 2;
    /** 单帧 base64 上限（1024×576 JPEG q55 约 0.1MB，留足余量） */
    static final int MAX_IMAGE_B64_CHARS = 2_000_000;
    static final Set<String> ALLOWED_IMAGE_MIME = Set.of("image/jpeg", "image/png");

    private static final Pattern TOOL_NAME_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    private final SimpMessagingTemplate messagingTemplate;
    private final ObjectMapper objectMapper;

    /**
     * 审计（可选注入）：单测环境与「没有数据库的极简部署」下都不能让桥装配失败。
     * 为 null 时 `finish()` 直接跳过审计。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private CapabilityAuditService auditService;

    @Value("${lianyu.tools.agent-bridge.enabled:true}")
    private boolean enabled;

    /**
     * 单步等待上限（兜底）。能力的真实超时由 Python 逐步传入（`timeoutMs`），
     * 这里只是「客户端失联时别把线程永久占住」的上限 —— 不再是 630s 那种整任务超时。
     */
    @Value("${lianyu.tools.agent-bridge.step-timeout-ms:20000}")
    private long stepTimeoutMs;

    private final Map<Long, BridgeSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, PendingCall> pendingCalls = new ConcurrentHashMap<>();

    /** 客户端注册的本地能力（inputSchema 为 JSON Schema 字符串） */
    public record ClientTool(String name, String description, String inputSchema, boolean dangerous) {
    }

    /**
     * 下发给客户端的动作载荷。
     *
     * <p>`timeoutMs/danger/evidence/stepId` 是能力层新增的：客户端据此做超时、确认与审计；
     * `requestId` 即 Python 生成的幂等 `stepId`（同一步重发不会执行两次）。
     */
    public record ToolCallPush(String type, String requestId, String name, String arguments,
                               Long characterId, String characterName, String characterAvatarUrl,
                               Long timeoutMs, String danger, String evidence, String sessionId) {
    }

    /** 一次桌面动作请求（由 Python 经内网端点发起）。 */
    public record ExecRequest(Long userId, String tool, String argumentsJson, Long timeoutMs,
                              String danger, String evidence,
                              Long characterId, String characterName, String characterAvatarUrl,
                              String sessionId, String stepId) {

        /** 旧调用方（无角色信息、无会话标识）。 */
        public ExecRequest(Long userId, String tool, String argumentsJson, Long timeoutMs,
                           String danger, String evidence) {
            this(userId, tool, argumentsJson, timeoutMs, danger, evidence, null, null, null, "", "");
        }

        /** 旧调用方（有角色信息，但仍然没有会话标识 —— 如已废弃的 dispatch）。 */
        public ExecRequest(Long userId, String tool, String argumentsJson, Long timeoutMs,
                           String danger, String evidence,
                           Long characterId, String characterName, String characterAvatarUrl) {
            this(userId, tool, argumentsJson, timeoutMs, danger, evidence,
                    characterId, characterName, characterAvatarUrl, "", "");
        }
    }

    /**
     * 一次桌面动作的结果。
     *
     * <p>`text` 是**给模型看的说明**（成功内容或失败文案），`images` 只给视觉分析用、
     * 绝不进文本上下文，因此不参与 {@link #sanitizeResult} 的截断与字符消毒。
     */
    public record BridgeResult(boolean ok, String text, List<Map<String, Object>> images,
                               Map<String, Object> policy, String errorCode, long elapsedMs) {

        public static BridgeResult failure(String errorCode, String text) {
            return new BridgeResult(false, text, List.of(), Map.of(), errorCode, 0L);
        }
    }

    private record PendingCall(Long userId, CompletableFuture<BridgeResult> future) {
    }

    private static final class BridgeSession {
        volatile List<ClientTool> tools;
        volatile long lastSeenMs;

        BridgeSession(List<ClientTool> tools) {
            this.tools = tools;
            this.lastSeenMs = System.currentTimeMillis();
        }

        boolean alive() {
            return System.currentTimeMillis() - lastSeenMs <= HEARTBEAT_TTL_MS;
        }
    }

    /** 注册（或整体替换）当前用户的本地能力清单。 */
    public void register(Long userId, RegisterAgentToolsRequest request) {
        if (!enabled) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Agent 能力桥未启用");
        }
        List<RegisterAgentToolsRequest.AgentToolSpec> specs =
                request != null && request.getTools() != null ? request.getTools() : List.of();
        if (specs.size() > MAX_TOOLS) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "工具数量超过上限 " + MAX_TOOLS);
        }
        List<ClientTool> tools = specs.stream().map(this::toClientTool).toList();
        long distinct = tools.stream().map(ClientTool::name).distinct().count();
        if (distinct != tools.size()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "工具名称重复");
        }
        sessions.put(userId, new BridgeSession(tools));
        log.info("Agent bridge registered: userId={}, tools={}", userId,
                tools.stream().map(ClientTool::name).toList());
    }

    /** 心跳续期；桥未注册时返回 false（客户端应重新注册）。 */
    public boolean heartbeat(Long userId) {
        BridgeSession session = sessions.get(userId);
        if (session == null) {
            return false;
        }
        session.lastSeenMs = System.currentTimeMillis();
        return true;
    }

    /** 注销：清除能力清单并让所有等待中的调用立即失败。 */
    public void unregister(Long userId) {
        sessions.remove(userId);
        pendingCalls.entrySet().removeIf(entry -> {
            if (userId.equals(entry.getValue().userId())) {
                entry.getValue().future().complete(BridgeResult.failure(
                        "desktop_offline", "（本地助手已断开，操作未执行）"));
                return true;
            }
            return false;
        });
        log.info("Agent bridge unregistered: userId={}", userId);
    }

    /** 当前用户可用的本地能力；桥关闭/离线时为空列表。 */
    public List<ClientTool> availableTools(Long userId) {
        if (!enabled || userId == null) {
            return List.of();
        }
        BridgeSession session = sessions.get(userId);
        if (session == null || !session.alive()) {
            return List.of();
        }
        return session.tools;
    }

    /** 桥是否在线（供状态查询接口与桌面能力开关使用）。 */
    public boolean isOnline(Long userId) {
        BridgeSession session = userId == null ? null : sessions.get(userId);
        return session != null && session.alive();
    }

    // ───────────────────────── 执行 ─────────────────────────

    /**
     * 派发一次桌面动作并阻塞等待结果。仅可在模型调用线程（无事务）中执行。
     *
     * <p>超时/离线/未知工具**不抛异常**：返回 {@link BridgeResult} 里的文案与错误码，
     * 让模型能向用户自然解释，而不是把 500 抛给前端。
     */
    public BridgeResult exec(ExecRequest request) {
        long startedAt = System.currentTimeMillis();
        Long userId = request == null ? null : request.userId();
        String toolName = request == null ? "" : String.valueOf(request.tool());

        BridgeSession session = userId == null ? null : sessions.get(userId);
        if (session == null || !session.alive()) {
            return finish(request, BridgeResult.failure("desktop_offline",
                    "（本地助手当前不在线，无法执行 " + toolName + "。请确认桌面端「电脑能力」已开启。）"), startedAt);
        }
        boolean known = session.tools.stream().anyMatch(t -> t.name().equals(toolName));
        if (!known) {
            return finish(request, BridgeResult.failure("unknown_tool",
                    "（本地助手没有名为 " + toolName + " 的工具）"), startedAt);
        }
        if (isHighRiskFromScreen(request.danger(), request.evidence())) {
            log.warn("Agent bridge refused R3 from screen content: userId={}, tool={}", userId, toolName);
            return finish(request, BridgeResult.failure("screen_content_high_risk",
                    "（该操作被安全策略拒绝：屏幕上的文字不能触发不可逆动作，需要用户亲口要求。）"), startedAt);
        }

        long waitMs = resolveTimeout(request.timeoutMs());
        String requestId = UUID.randomUUID().toString();
        String args = AgentToolArguments.normalizeJson(request.argumentsJson(), objectMapper);
        CompletableFuture<BridgeResult> future = new CompletableFuture<>();
        pendingCalls.put(requestId, new PendingCall(userId, future));
        try {
            messagingTemplate.convertAndSendToUser(userId.toString(), QUEUE_DESTINATION,
                    new ToolCallPush("tool_call", requestId, toolName, args,
                            request.characterId(), request.characterName(), request.characterAvatarUrl(),
                            waitMs, request.danger(), request.evidence(), sessionIdOf(request)));
            log.info("Agent bridge exec: userId={}, tool={}, requestId={}, waitMs={}, args={}",
                    userId, toolName, requestId, waitMs, AgentToolArguments.preview(args));
            BridgeResult result = future.get(waitMs, TimeUnit.MILLISECONDS);
            return finish(request, new BridgeResult(result.ok(), result.text(), result.images(),
                    result.policy(), result.errorCode(),
                    Math.max(result.elapsedMs(), System.currentTimeMillis() - startedAt)), startedAt);
        } catch (TimeoutException e) {
            log.warn("Agent bridge step timeout: userId={}, tool={}, requestId={}", userId, toolName, requestId);
            return finish(request, BridgeResult.failure("step_timeout",
                    "（本地工具 " + toolName + " 执行超时或无响应，请检查桌面端是否卡住或已离线）"), startedAt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return finish(request, BridgeResult.failure("interrupted", "（本地工具调用被中断）"), startedAt);
        } catch (Exception e) {
            log.warn("Agent bridge exec failed: userId={}, tool={}", userId, toolName, e);
            return finish(request, BridgeResult.failure("dispatch_failed",
                    "（本地工具 " + toolName + " 调用失败）"), startedAt);
        } finally {
            pendingCalls.remove(requestId);
        }
    }

    /**
     * 统一收尾：写一条审计再返回。
     *
     * <p>审计失败只记 WARN（{@link CapabilityAuditService} 内部兜底），绝不影响这次动作的结论 ——
     * 「审计表没建好就点不动电脑」是不可接受的降级方式。
     */
    private BridgeResult finish(ExecRequest request, BridgeResult result, long startedAt) {
        if (auditService == null || request == null || result == null) {
            return result;
        }
        long elapsed = result.elapsedMs() > 0 ? result.elapsedMs() : System.currentTimeMillis() - startedAt;
        auditService.record(new CapabilityAuditService.AuditEntry(
                request.userId(),
                sessionIdOf(request),
                stepIdOf(request),
                request.tool(),
                CapabilityAuditService.digestArgs(request.argumentsJson()),
                request.danger() == null ? "" : request.danger(),
                request.evidence() == null ? "" : request.evidence(),
                CapabilityAuditService.confirmedFrom(result.policy()),
                result.ok(),
                result.errorCode(),
                elapsed));
        return result;
    }

    /** `stepId` 形如 `{sessionId}#{n}`：优先用显式 sessionId，缺省时取前缀。 */
    static String sessionIdOf(ExecRequest request) {
        if (request == null) {
            return "";
        }
        if (request.sessionId() != null && !request.sessionId().isBlank()) {
            return request.sessionId();
        }
        String stepId = stepIdOf(request);
        int idx = stepId.indexOf('#');
        return idx > 0 ? stepId.substring(0, idx) : "";
    }

    static String stepIdOf(ExecRequest request) {
        return request != null && request.stepId() != null ? request.stepId() : "";
    }

    /** 危险来源拦截：R3 只能由用户亲口要求触发（双保险，Python 侧已拦一次）。 */
    static boolean isHighRiskFromScreen(String danger, String evidence) {
        return danger != null && "R3".equalsIgnoreCase(danger)
                && evidence != null && "screen_content".equalsIgnoreCase(evidence);
    }

    /**
     * 进度旁路：Python 每步开始上报一句现场解说，直推渲染端（角色进度气泡）。
     *
     * <p>文案由 Python 生成（提示文案唯一产地），这里只做搬运。
     * 桥离线时不推（渲染端本来也不该看到进度）。
     */
    public void relayProgress(Long userId, String sessionId, String stepId, String code, String text) {
        if (userId == null || text == null || text.isBlank()) {
            return;
        }
        java.util.Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("type", "tool_progress");
        payload.put("requestId", stepId == null ? "" : stepId);
        payload.put("sessionId", sessionId == null ? "" : sessionId);
        payload.put("code", code == null ? "" : code);
        payload.put("text", text);
        messagingTemplate.convertAndSendToUser(userId.toString(), PROGRESS_DESTINATION, payload);
    }

    /** 用户按 Esc / 任务结束：通知客户端中止当前动作（客户端自己也会因 Esc 本地中止）。 */
    public void pushCancel(Long userId, String stepId) {
        if (userId == null) {
            return;
        }
        java.util.Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("type", "tool_cancel");
        payload.put("requestId", stepId == null ? "" : stepId);
        messagingTemplate.convertAndSendToUser(userId.toString(), QUEUE_DESTINATION, payload);
    }

    private long resolveTimeout(Long requested) {
        long cap = stepTimeoutMs > 0 ? stepTimeoutMs : 20_000L;
        if (requested == null || requested <= 0) {
            return cap;
        }
        return Math.min(requested, cap);
    }

    /** 客户端回传结果（兼容旧客户端：无图片、无策略）。 */
    public void completeResult(Long userId, String requestId, boolean ok, String content, String error) {
        completeResult(userId, requestId, ok, content, error, null, null);
    }

    /** 客户端回传结果；校验 requestId 归属，防止跨用户伪造。 */
    public void completeResult(Long userId, String requestId, boolean ok, String content, String error,
                               List<Map<String, Object>> images, Map<String, Object> policy) {
        if (requestId == null || requestId.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "requestId 不能为空");
        }
        PendingCall pending = pendingCalls.get(requestId);
        if (pending == null) {
            log.debug("Agent bridge result for unknown/expired request: {}", requestId);
            return;
        }
        if (!pending.userId().equals(userId)) {
            log.warn("Agent bridge result user mismatch: requestId={}, expected={}, got={}",
                    requestId, pending.userId(), userId);
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权提交该调用结果");
        }
        String text;
        String errorCode = "";
        if (ok) {
            text = sanitizeResult(content);
            if (text.isBlank()) {
                text = "（本地工具执行完成，无输出）";
            }
            log.info("Agent bridge result: userId={}, requestId={}, ok=true, chars={}, images={}",
                    userId, requestId, text.length(), images == null ? 0 : images.size());
        } else {
            errorCode = error == null || error.isBlank() ? "unknown" : sanitizeResult(error);
            if (errorCode.length() > 64) {
                errorCode = errorCode.substring(0, 64);
            }
            text = "（本地工具执行失败：" + errorCode + "）";
            log.warn("Agent bridge result: userId={}, requestId={}, error={}",
                    userId, requestId, errorCode);
        }
        pending.future().complete(new BridgeResult(ok, text, sanitizeImages(images),
                policy == null ? Map.of() : policy, errorCode, 0L));
    }

    /**
     * 图片消毒：**只校验不裁剪内容**。mime 白名单 + 单帧大小上限；
     * 不合法的帧直接丢弃（图挂了不该让整个动作失败）。
     */
    static List<Map<String, Object>> sanitizeImages(List<Map<String, Object>> images) {
        if (images == null || images.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> kept = new ArrayList<>();
        for (Map<String, Object> image : images) {
            if (image == null) {
                continue;
            }
            Object mime = image.get("mime");
            Object b64 = image.get("b64");
            if (!(b64 instanceof String data) || data.isEmpty()) {
                continue;
            }
            String normalized = mime == null ? "image/jpeg" : String.valueOf(mime).toLowerCase(Locale.ROOT);
            if (!ALLOWED_IMAGE_MIME.contains(normalized)) {
                log.warn("Agent bridge dropped image with mime={}", normalized);
                continue;
            }
            if (data.length() > MAX_IMAGE_B64_CHARS) {
                log.warn("Agent bridge dropped oversized image: chars={}", data.length());
                continue;
            }
            if (kept.size() >= MAX_IMAGES) {
                break;
            }
            kept.add(Map.of(
                    "mime", normalized,
                    "b64", data,
                    "w", image.getOrDefault("w", 0),
                    "h", image.getOrDefault("h", 0),
                    "scaleFactor", image.getOrDefault("scaleFactor", 1)));
        }
        return List.copyOf(kept);
    }

    private ClientTool toClientTool(RegisterAgentToolsRequest.AgentToolSpec spec) {
        if (spec == null || spec.getName() == null || !TOOL_NAME_PATTERN.matcher(spec.getName()).matches()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "工具名称非法（仅限字母/数字/下划线/中划线，长度 1-" + MAX_NAME_LENGTH + "）");
        }
        String description = spec.getDescription() == null ? "" : spec.getDescription().trim();
        if (description.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "工具 " + spec.getName() + " 缺少描述");
        }
        if (description.length() > MAX_DESCRIPTION_LENGTH) {
            description = description.substring(0, MAX_DESCRIPTION_LENGTH);
        }
        String schema = serializeSchema(spec.getName(), spec.getInputSchema());
        return new ClientTool(spec.getName(), description, schema, Boolean.TRUE.equals(spec.getDangerous()));
    }

    private String serializeSchema(String toolName, JsonNode schema) {
        if (schema == null || schema.isNull()) {
            return "{\"type\":\"object\",\"properties\":{}}";
        }
        if (!schema.isObject()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "工具 " + toolName + " 的 inputSchema 必须是 JSON 对象");
        }
        try {
            String json = objectMapper.writeValueAsString(schema);
            if (json.length() > MAX_SCHEMA_LENGTH) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "工具 " + toolName + " 的 inputSchema 过大");
            }
            return json;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "工具 " + toolName + " 的 inputSchema 无法解析");
        }
    }

    /**
     * 工具结果消毒：本地工具输出属于不可信内容（可能包含屏幕/网页文本），
     * 剥离控制字符并截断，降低提示注入与上下文爆量风险。
     */
    static String sanitizeResult(String raw) {
        if (raw == null) {
            return "";
        }
        String cleaned = raw.replaceAll("[\\p{Cc}&&[^\\n\\t\\r]]", "").trim();
        if (cleaned.length() > MAX_RESULT_CHARS) {
            cleaned = cleaned.substring(0, MAX_RESULT_CHARS) + "\n（输出过长，已截断）";
        }
        return cleaned;
    }
}
