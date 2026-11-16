package com.lianyu.web.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.lianyu.common.base.Result;
import com.lianyu.service.qq.QqBotConnectService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * App 侧「用自己的 QQ 机器人」入口（BYO）。
 *
 * <p>流程：startTask 拿二维码 → 前端画出来让用户用手机 QQ 扫 → pollTask 轮询到
 * completed → bindCharacter 选角色。扫码那一刻腾讯已经把 openid 给了我们，
 * 所以这里没有「把 6 位码发给机器人」那一步。
 */
@Tag(name = "QQ", description = "QQ 官方机器人（用户自带）")
@RestController
@RequestMapping("/api/qq/bot")
@RequiredArgsConstructor
public class QqBotController {

    private final QqBotConnectService qqBotConnectService;

    public record StartTaskRequest(String source) {
    }

    public record BindRequest(Long characterId) {
    }

    @Operation(summary = "生成扫码连接任务（返回二维码内容，15 分钟有效）")
    @PostMapping("/task")
    public Result<QqBotConnectService.TaskView> startTask(@RequestBody(required = false) StartTaskRequest request) {
        long userId = StpUtil.getLoginIdAsLong();
        String source = request == null ? null : request.source();
        return Result.ok(qqBotConnectService.startTask(userId, source));
    }

    @Operation(summary = "轮询扫码结果：pending / completed / expired / unknown")
    @GetMapping("/task/{taskId}")
    public Result<QqBotConnectService.TaskStatus> pollTask(@PathVariable("taskId") String taskId) {
        long userId = StpUtil.getLoginIdAsLong();
        return Result.ok(qqBotConnectService.pollTask(userId, taskId));
    }

    @Operation(summary = "我名下已连接的机器人")
    @GetMapping("/accounts")
    public Result<List<QqBotConnectService.AccountView>> listAccounts() {
        long userId = StpUtil.getLoginIdAsLong();
        return Result.ok(qqBotConnectService.listAccounts(userId));
    }

    @Operation(summary = "把机器人绑到某个角色（同时建/复用会话）")
    @PostMapping("/accounts/{appId}/bind")
    public Result<QqBotConnectService.AccountView> bind(@PathVariable("appId") String appId,
                                                        @RequestBody BindRequest request) {
        long userId = StpUtil.getLoginIdAsLong();
        return Result.ok(qqBotConnectService.bindCharacter(userId, appId, request.characterId()));
    }

    @Operation(summary = "断开机器人（解绑 + 删除凭据）")
    @DeleteMapping("/accounts/{appId}")
    public Result<Boolean> remove(@PathVariable("appId") String appId) {
        long userId = StpUtil.getLoginIdAsLong();
        return Result.ok(qqBotConnectService.removeAccount(userId, appId));
    }
}
