package com.lianyu.web.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.lianyu.common.base.Result;
import com.lianyu.service.agent.ModelQuotaGuard;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 模型额度状态：让客户端能解释「为什么她不主动找我了」。
 *
 * <p>主动关怀在额度为 0 时会被 {@link ModelQuotaGuard} 自动熔断（并按 10 分钟→24 小时
 * 阶梯退避重试），用户充值后第一次成功调用即自动恢复。前端据此显示一条提示，
 * 而不是让用户以为角色坏了。
 */
@Tag(name = "Model", description = "模型额度与可用性")
@RestController
@RequestMapping("/api/model")
@RequiredArgsConstructor
public class ModelQuotaController {

    private final ModelQuotaGuard modelQuotaGuard;

    @Operation(summary = "当前用户的模型额度熔断状态")
    @GetMapping("/quota")
    public Result<Map<String, Object>> quota() {
        long userId = StpUtil.getLoginIdAsLong();
        return Result.ok(modelQuotaGuard.snapshot(userId));
    }
}
