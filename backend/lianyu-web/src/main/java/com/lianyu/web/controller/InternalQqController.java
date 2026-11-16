package com.lianyu.web.controller;

import com.lianyu.common.base.Result;
import com.lianyu.service.dto.InternalQqBindRequest;
import com.lianyu.service.dto.InternalQqTurnRequest;
import com.lianyu.service.qq.QqBotConnectService;
import com.lianyu.service.qq.QqChannelService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 网关 → 后端。**不带任何用户凭据**：身份靠 channel + externalId（QQ openid）解析。
 *
 * <p>调用方是 channel-gateway 容器，鉴权在 {@code InternalSignatureFilter}（HMAC + 时间窗 + nonce）。
 *
 * <p>BYO 之后网关还要两件事：知道「有哪些机器人该连」（accounts），以及按 appId 换
 * 短期 accessToken（token）——appSecret 始终留在库里，不经过网络。
 */
@RestController
@RequestMapping("/internal/qq")
@RequiredArgsConstructor
public class InternalQqController {

    private final QqChannelService qqChannelService;
    private final QqBotConnectService qqBotConnectService;

    public record TokenRequest(String appId) {
    }

    public record ReportRequest(String appId, String error) {
    }

    @PostMapping("/turn")
    public Result<QqChannelService.TurnResult> turn(@Valid @RequestBody InternalQqTurnRequest request) {
        return Result.ok(qqChannelService.turn(
                request.getChannel(),
                request.getExternalId(),
                request.getText(),
                request.getImageBase64(),
                request.getImageContentType()));
    }

    @PostMapping("/bind")
    public Result<QqChannelService.BindResult> bind(@Valid @RequestBody InternalQqBindRequest request) {
        return Result.ok(qqChannelService.bind(request.getChannel(), request.getExternalId(), request.getCode()));
    }

    @PostMapping("/accounts")
    public Result<List<QqBotConnectService.InternalAccount>> accounts() {
        return Result.ok(qqBotConnectService.activeAccounts());
    }

    @PostMapping("/token")
    public Result<QqBotConnectService.TokenView> token(@RequestBody TokenRequest request) {
        return Result.ok(qqBotConnectService.issueAccessToken(request.appId()));
    }

    @PostMapping("/report")
    public Result<Boolean> report(@RequestBody ReportRequest request) {
        qqBotConnectService.markError(request.appId(), request.error());
        return Result.ok(true);
    }
}
