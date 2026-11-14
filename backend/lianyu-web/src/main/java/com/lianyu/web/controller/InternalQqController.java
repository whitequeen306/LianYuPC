package com.lianyu.web.controller;

import com.lianyu.common.base.Result;
import com.lianyu.service.dto.InternalQqBindRequest;
import com.lianyu.service.dto.InternalQqTurnRequest;
import com.lianyu.service.qq.QqChannelService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 网关 → 后端。**不带任何用户凭据**：身份靠 channel + externalId（QQ openid）解析。
 *
 * <p>调用方是 channel-gateway 容器，鉴权在 {@code InternalSignatureFilter}（HMAC + 时间窗 + nonce）。
 */
@RestController
@RequestMapping("/internal/qq")
@RequiredArgsConstructor
public class InternalQqController {

    private final QqChannelService qqChannelService;

    @PostMapping("/turn")
    public Result<QqChannelService.TurnResult> turn(@Valid @RequestBody InternalQqTurnRequest request) {
        return Result.ok(qqChannelService.turn(request.getChannel(), request.getExternalId(), request.getText()));
    }

    @PostMapping("/bind")
    public Result<QqChannelService.BindResult> bind(@Valid @RequestBody InternalQqBindRequest request) {
        return Result.ok(qqChannelService.bind(request.getChannel(), request.getExternalId(), request.getCode()));
    }
}
