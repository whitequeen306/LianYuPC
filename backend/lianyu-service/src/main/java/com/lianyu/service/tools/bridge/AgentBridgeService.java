package com.lianyu.service.tools.bridge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lianyu.common.base.ErrorCode;
import com.lianyu.common.exception.BusinessException;
import com.lianyu.service.dto.RegisterAgentToolsRequest;
import com.lianyu.service.tools.ChatToolContext;
import java.util.List;
import java.util.Map;
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
 * Agent 工具桥：桌面客户端把本地 MCP 工具清单注册到云端，模型在对话中发起工具调用时，
 * 经 STOMP 用户队列（{@code /user/queue/agent-tools}）下发到客户端本地执行，客户端将结果
 * POST 回 {@code /api/agent-bridge/result} 完成闭环。
 *
 * <p>会话为内存态（单实例部署）：注册 + 30s 心跳维持，超过 {@link #HEARTBEAT_TTL_MS} 视为离线，
 * 工具自动从模型可见列表消失。
 *
 * <p>红线遵守：{@link #dispatch} 会阻塞等待客户端结果（最长 call-timeout-ms），
 * 只允许在无事务的模型调用线程内使用（与 AI 慢调用同级），绝不允许进入 @Transactional。
 * 超时/离线返回错误文案而不抛异常，让模型能向用户自然解释失败。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentBridgeService {

    public static final String QUEUE_DESTINATION = "/queue/agent-tools";

    /** 心跳有效期：客户端每 30s 心跳一次，90s 未见即离线 */
    static final long HEARTBEAT_TTL_MS = 90_000L;
    static final int MAX_TOOLS = 32;
    static final int MAX_NAME_LENGTH = 64;
    static final int MAX_DESCRIPTION_LENGTH = 1024;
    static final int MAX_SCHEMA_LENGTH = 8_192;
    static final int MAX_RESULT_CHARS = 8_000;

    private static final Pattern TOOL_NAME_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    private final SimpMessagingTemplate messagingTemplate;
    private final ObjectMapper objectMapper;

    @Value("${lianyu.tools.agent-bridge.enabled:true}")
    private boolean enabled;

    @Value("${lianyu.tools.agent-bridge.call-timeout-ms:630000}")
    private long callTimeoutMs;

    private final Map<Long, BridgeSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, PendingCall> pendingCalls = new ConcurrentHashMap<>();

    /** 客户端注册的本地工具（inputSchema 为 JSON Schema 字符串） */
    public record ClientTool(String name, String description, String inputSchema, boolean dangerous) {
    }

    /** 下发给客户端的工具调用载荷（含本轮角色，供桌面端控制条展示） */
    public record ToolCallPush(String type, String requestId, String name, String arguments,
                               Long characterId, String characterName, String characterAvatarUrl) {
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

    private record PendingCall(Long userId, CompletableFuture<String> future) {
    }

    /** 注册（或整体替换）当前用户的本地工具清单。 */
    public void register(Long userId, RegisterAgentToolsRequest request) {
        if (!enabled) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Agent 工具桥未启用");
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

    /** 注销：清除工具清单并让所有等待中的调用立即失败。 */
    public void unregister(Long userId) {
        sessions.remove(userId);
        pendingCalls.entrySet().removeIf(entry -> {
            if (userId.equals(entry.getValue().userId())) {
                entry.getValue().future().complete("（本地助手已断开，操作未执行）");
                return true;
            }
            return false;
        });
        log.info("Agent bridge unregistered: userId={}", userId);
    }

    /** 当前用户可用的本地工具；桥关闭/离线时为空列表。 */
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

    /** 桥是否在线（供状态查询接口）。 */
    public boolean isOnline(Long userId) {
        BridgeSession session = userId == null ? null : sessions.get(userId);
        return session != null && session.alive();
    }

    /**
     * 派发一次工具调用到用户桌面端并阻塞等待结果。
     * 仅可在模型调用线程（无事务）中执行；超时/离线返回错误文案。
     */
    public String dispatch(Long userId, String toolName, String argumentsJson) {
        BridgeSession session = sessions.get(userId);
        if (session == null || !session.alive()) {
            return "（本地助手当前不在线，无法执行 " + toolName + "）";
        }
        boolean known = session.tools.stream().anyMatch(t -> t.name().equals(toolName));
        if (!known) {
            return "（本地助手没有名为 " + toolName + " 的工具）";
        }

        String requestId = UUID.randomUUID().toString();
        CompletableFuture<String> future = new CompletableFuture<>();
        pendingCalls.put(requestId, new PendingCall(userId, future));
        try {
            ChatToolContext.Scope scope = ChatToolContext.current();
            Long characterId = scope != null ? scope.characterId() : null;
            String characterName = scope != null ? scope.characterName() : null;
            String characterAvatarUrl = scope != null ? scope.characterAvatarUrl() : null;
            messagingTemplate.convertAndSendToUser(userId.toString(), QUEUE_DESTINATION,
                    new ToolCallPush("tool_call", requestId, toolName,
                            argumentsJson == null ? "{}" : argumentsJson,
                            characterId, characterName, characterAvatarUrl));
            log.info("Agent bridge dispatch: userId={}, tool={}, requestId={}", userId, toolName, requestId);
            return future.get(callTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            log.warn("Agent bridge call timeout: userId={}, tool={}, requestId={}", userId, toolName, requestId);
            return "（本地工具 " + toolName + " 执行超时或无响应，请检查桌面端 MCP 服务）";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "（本地工具调用被中断）";
        } catch (Exception e) {
            log.warn("Agent bridge call failed: userId={}, tool={}", userId, toolName, e);
            return "（本地工具 " + toolName + " 调用失败）";
        } finally {
            pendingCalls.remove(requestId);
        }
    }

    /** 客户端回传执行结果；校验 requestId 归属，防止跨用户伪造。 */
    public void completeResult(Long userId, String requestId, boolean ok, String content, String error) {
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
        String text = ok
                ? sanitizeResult(content)
                : "（本地工具执行失败：" + sanitizeResult(error == null || error.isBlank() ? "未知错误" : error) + "）";
        if (text.isBlank()) {
            text = "（本地工具执行完成，无输出）";
        }
        pending.future().complete(text);
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
