package com.lianyu.web.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.lianyu.common.base.Result;
import com.lianyu.service.dto.IssueQqBindCodeRequest;
import com.lianyu.service.qq.QqChannelService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
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
 * App 侧的 QQ 绑定入口：生成绑定码、看已绑定的 QQ 号、解绑。
 */
@Tag(name = "QQ", description = "QQ 渠道绑定")
@RestController
@RequestMapping("/api/qq")
@RequiredArgsConstructor
public class QqChannelController {

    private final QqChannelService qqChannelService;

    @Operation(summary = "生成绑定码（10 分钟有效，一次性）")
    @PostMapping("/bind-code")
    public Result<QqChannelService.BindCodeView> issueBindCode(@Valid @RequestBody IssueQqBindCodeRequest request) {
        long userId = StpUtil.getLoginIdAsLong();
        return Result.ok(qqChannelService.issueCode(userId, request.getCharacterId()));
    }

    @Operation(summary = "已绑定的 QQ 号")
    @GetMapping("/bindings")
    public Result<List<QqChannelService.BindingView>> listBindings() {
        long userId = StpUtil.getLoginIdAsLong();
        return Result.ok(qqChannelService.listBindings(userId));
    }

    @Operation(summary = "解绑")
    @DeleteMapping("/bindings/{id}")
    public Result<Boolean> unbind(@PathVariable("id") Long id) {
        long userId = StpUtil.getLoginIdAsLong();
        return Result.ok(qqChannelService.unbind(userId, id));
    }
}
