package com.lianyu.web.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.lianyu.common.base.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 已下线接口的**过渡垫片**（只为让存量客户端闭嘴，不含任何业务逻辑）。
 *
 * <p>2026-10-17（{@code 74552b2}）下线社区功能，`/api/community/*` 整套移除，
 * 由「动态」接替。但**存量桌面客户端**（2026-10-07 及更早打的包）在每次通知
 * WebSocket 建连时仍会 `POST /api/community/push-catchup`，而该调用没有带
 * {@code skipGlobalError}，于是每次重连都会弹一次全局错误 toast —— 用户看到的是
 * 「服务暂时不可用，请稍后再试」，但后端其实一点没坏，只是版本对不上。
 *
 * <p>这个端点返回 200 让老客户端安静下来：客户端 `await dg()` **丢弃返回值**，
 * 所以这里不需要任何真实语义。真正的修法是客户端升级到已下线社区的版本。
 *
 * <p><b>删除条件</b>：线上最小可运行客户端版本 ≥ 关停社区的那个版本后，直接删掉本类。
 * 不要在这里加业务逻辑 —— 它存在的唯一理由就是兼容一个已经不存在的功能。
 */
@Slf4j
@RestController
@RequestMapping("/api/community")
public class LegacyCommunityController {

    @PostMapping("/push-catchup")
    public Result<Void> pushCatchup() {
        StpUtil.checkLogin();
        log.info("legacy client still polls /api/community/push-catchup — 建议升级客户端");
        return Result.ok();
    }
}
