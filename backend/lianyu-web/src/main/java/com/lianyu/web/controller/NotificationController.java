package com.lianyu.web.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.lianyu.common.base.Result;
import com.lianyu.service.NotificationService;
import com.lianyu.service.dto.MarkNotificationReadRequest;
import com.lianyu.service.dto.NotificationResponse;
import com.lianyu.service.dto.PushSubscriptionRequest;
import com.lianyu.service.dto.UnreadCountResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Tag(name = "Notification", description = "通知与推送")
@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    @Operation(summary = "通知列表")
    @GetMapping
    public Result<List<NotificationResponse>> list(
            @RequestParam(value = "unreadOnly", defaultValue = "false") boolean unreadOnly,
            @RequestParam(value = "limit", defaultValue = "20") int limit
    ) {
        long userId = StpUtil.getLoginIdAsLong();
        return Result.ok(notificationService.list(userId, unreadOnly, limit));
    }

    @Operation(summary = "未读数")
    @GetMapping("/unread-count")
    public Result<UnreadCountResponse> unreadCount() {
        long userId = StpUtil.getLoginIdAsLong();
        return Result.ok(notificationService.getUnreadCount(userId));
    }

    @Operation(summary = "标记已读")
    @PostMapping("/mark-read")
    public Result<Void> markRead(@RequestBody(required = false) MarkNotificationReadRequest request) {
        long userId = StpUtil.getLoginIdAsLong();
        notificationService.markRead(userId, request);
        return Result.ok();
    }

    @Operation(summary = "获取 Web Push 公钥")
    @GetMapping("/push/public-key")
    public Result<Map<String, String>> getPublicKey() {
        return Result.ok(Map.of("publicKey", notificationService.getVapidPublicKey()));
    }

    @Operation(summary = "订阅 Web Push")
    @PostMapping("/push/subscribe")
    public Result<Void> subscribe(@Valid @RequestBody PushSubscriptionRequest request) {
        long userId = StpUtil.getLoginIdAsLong();
        notificationService.upsertPushSubscription(userId, request);
        return Result.ok();
    }

    @Operation(summary = "取消 Web Push")
    @PostMapping("/push/unsubscribe")
    public Result<Void> unsubscribe(@RequestBody Map<String, String> request) {
        long userId = StpUtil.getLoginIdAsLong();
        notificationService.disablePushSubscription(userId, request.get("endpoint"));
        return Result.ok();
    }
}
