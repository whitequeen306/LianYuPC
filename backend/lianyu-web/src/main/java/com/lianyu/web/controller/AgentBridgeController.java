package com.lianyu.web.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.lianyu.common.base.Result;
import com.lianyu.service.dto.AgentToolResultRequest;
import com.lianyu.service.dto.RegisterAgentToolsRequest;
import com.lianyu.service.tools.bridge.AgentBridgeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Agent 工具桥：桌面客户端注册/心跳/回传结果。
 * 工具调用的下发走 STOMP 用户队列 {@code /user/queue/agent-tools}。
 */
@Tag(name = "AgentBridge", description = "桌面 Agent 工具桥（MCP 本地工具接入）")
@RestController
@RequestMapping("/api/agent-bridge")
@RequiredArgsConstructor
public class AgentBridgeController {

    private final AgentBridgeService agentBridgeService;

    @Operation(summary = "注册/替换本地工具清单")
    @PostMapping("/tools")
    public Result<Void> registerTools(@RequestBody RegisterAgentToolsRequest request) {
        long userId = StpUtil.getLoginIdAsLong();
        agentBridgeService.register(userId, request);
        return Result.ok();
    }

    @Operation(summary = "桥心跳（30s 一次维持在线）")
    @PostMapping("/heartbeat")
    public Result<Map<String, Object>> heartbeat() {
        long userId = StpUtil.getLoginIdAsLong();
        boolean known = agentBridgeService.heartbeat(userId);
        return Result.ok(Map.of("registered", known));
    }

    @Operation(summary = "注销本地工具桥")
    @DeleteMapping
    public Result<Void> unregister() {
        long userId = StpUtil.getLoginIdAsLong();
        agentBridgeService.unregister(userId);
        return Result.ok();
    }

    @Operation(summary = "回传本地工具执行结果")
    @PostMapping("/result")
    public Result<Void> submitResult(@Valid @RequestBody AgentToolResultRequest request) {
        long userId = StpUtil.getLoginIdAsLong();
        agentBridgeService.completeResult(userId, request.getRequestId(),
                Boolean.TRUE.equals(request.getOk()), request.getContent(), request.getError());
        return Result.ok();
    }

    @Operation(summary = "查询桥在线状态")
    @GetMapping("/status")
    public Result<Map<String, Object>> status() {
        long userId = StpUtil.getLoginIdAsLong();
        return Result.ok(Map.of(
                "online", agentBridgeService.isOnline(userId),
                "toolCount", agentBridgeService.availableTools(userId).size()));
    }
}
