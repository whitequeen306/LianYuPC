package com.lianyu.web.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.lianyu.common.base.ErrorCode;
import com.lianyu.common.base.Result;
import com.lianyu.common.exception.BusinessException;
import com.lianyu.service.auth.AuthRateLimiter;
import com.lianyu.service.conversation.ConversationService;
import com.lianyu.service.storage.FileStorageService;
import com.lianyu.service.dto.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

@Tag(name = "Conversation", description = "会话与消息")
@RestController
@RequestMapping("/api/conversation")
@RequiredArgsConstructor
public class ConversationController {

    private final ConversationService conversationService;
    private final FileStorageService fileStorageService;
    private final AuthRateLimiter authRateLimiter;
    @Value("${lianyu.auth.rate-limit.messages-per-user-per-minute:60}")
    private int messagesPerUserPerMinute;

    @Operation(summary = "创建会话")
    @PostMapping
    public Result<ConversationResponse> create(@Valid @RequestBody CreateConversationRequest request) {
        long userId = StpUtil.getLoginIdAsLong();
        return Result.ok(conversationService.create(userId, request));
    }

    @Operation(summary = "会话列表")
    @GetMapping
    public Result<List<ConversationResponse>> list() {
        long userId = StpUtil.getLoginIdAsLong();
        return Result.ok(conversationService.list(userId));
    }

    @Operation(summary = "获取会话")
    @GetMapping("/{id}")
    public Result<ConversationResponse> get(@PathVariable("id") Long id) {
        long userId = StpUtil.getLoginIdAsLong();
        return Result.ok(conversationService.get(userId, id));
    }

    @Operation(summary = "删除会话")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable("id") Long id) {
        long userId = StpUtil.getLoginIdAsLong();
        conversationService.delete(userId, id);
        return Result.ok();
    }

    @Operation(summary = "清空会话消息（保留会话行，ID 不变）")
    @DeleteMapping("/{id}/messages")
    public Result<Void> clearMessages(@PathVariable("id") Long id) {
        long userId = StpUtil.getLoginIdAsLong();
        conversationService.clearMessages(userId, id);
        return Result.ok();
    }

    @Operation(summary = "上传聊天图片（单图）")
    @PostMapping("/chat-image")
    public Result<Map<String, String>> uploadChatImage(@RequestParam("file") MultipartFile file) {
        StpUtil.checkLogin();
        long userId = StpUtil.getLoginIdAsLong();
        authRateLimiter.checkRateLimit("rate:msg:", String.valueOf(userId),
                messagesPerUserPerMinute, java.time.Duration.ofMinutes(1), "发送消息过于频繁，请稍后再试");
        String imageUrl = fileStorageService.uploadChatImage(file);
        return Result.ok(Map.of("imageUrl", imageUrl));
    }

    @Operation(summary = "发送消息（非流式）")
    @PostMapping("/{id}/messages")
    public Result<MessageResponse> sendMessage(@PathVariable("id") Long id,
                                               @Valid @RequestBody SendMessageRequest request) {
        long userId = StpUtil.getLoginIdAsLong();
        authRateLimiter.checkRateLimit("rate:msg:", String.valueOf(userId),
                messagesPerUserPerMinute, java.time.Duration.ofMinutes(1), "发送消息过于频繁，请稍后再试");
        return Result.ok(conversationService.sendMessage(userId, id, request));
    }

    @Operation(summary = "发送消息（SSE流式）")
    @PostMapping(value = "/{id}/messages/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter sendMessageStream(@PathVariable("id") Long id,
                                         @Valid @RequestBody SendMessageRequest request) {
        long userId = StpUtil.getLoginIdAsLong();
        authRateLimiter.checkRateLimit("rate:msg:", String.valueOf(userId),
                messagesPerUserPerMinute, java.time.Duration.ofMinutes(1), "发送消息过于频繁，请稍后再试");
        return conversationService.sendMessageStream(userId, id, request);
    }

    @Operation(summary = "获取消息列表（分页）")
    @GetMapping("/{id}/messages")
    public Result<MessagePageResponse> getMessages(
            @PathVariable("id") Long id,
            @RequestParam(required = false) Long beforeSeq,
            @RequestParam(defaultValue = "50") int limit) {
        long userId = StpUtil.getLoginIdAsLong();
        return Result.ok(conversationService.getMessages(userId, id, beforeSeq, limit));
    }
}
