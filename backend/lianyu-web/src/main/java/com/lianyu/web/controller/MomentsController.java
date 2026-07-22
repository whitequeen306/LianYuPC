package com.lianyu.web.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.lianyu.common.base.Result;
import com.lianyu.service.moments.MomentsCommentService;
import com.lianyu.service.moments.MomentsService;
import com.lianyu.service.dto.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Moments", description = "角色朋友圈")
@RestController
@RequestMapping("/api/moments")
@RequiredArgsConstructor
public class MomentsController {

    private final MomentsService momentsService;
    private final MomentsCommentService momentsCommentService;

    @Operation(summary = "发表用户动态")
    @PostMapping
    public Result<MomentPostResponse> createPost(@RequestBody @jakarta.validation.Valid CreateMomentPostRequest request) {
        long userId = StpUtil.getLoginIdAsLong();
        return Result.ok(momentsService.createUserPost(userId, request));
    }

    @Operation(summary = "朋友圈动态流")
    @GetMapping
    public Result<MomentFeedResponse> feed(
            @RequestParam(value = "characterId", required = false) Long characterId,
            @RequestParam(value = "cursor", required = false) Long cursor,
            @RequestParam(value = "limit", defaultValue = "20") int limit
    ) {
        long userId = StpUtil.getLoginIdAsLong();
        return Result.ok(momentsService.listFeed(userId, characterId, cursor, limit));
    }

    @Operation(summary = "我发布的角色动态归档（仅本人）")
    @GetMapping("/mine")
    public Result<MomentFeedResponse> mine(
            @RequestParam(value = "cursor", required = false) Long cursor,
            @RequestParam(value = "limit", defaultValue = "5") int limit
    ) {
        long userId = StpUtil.getLoginIdAsLong();
        return Result.ok(momentsService.listMyUserPosts(userId, cursor, limit));
    }

    @Operation(summary = "未读动态数")
    @GetMapping("/unread-count")
    public Result<MomentUnreadCountResponse> unreadCount() {
        long userId = StpUtil.getLoginIdAsLong();
        return Result.ok(momentsService.getUnreadCount(userId));
    }

    @Operation(summary = "标记朋友圈已读")
    @PostMapping("/mark-seen")
    public Result<Void> markSeen() {
        long userId = StpUtil.getLoginIdAsLong();
        momentsService.markFeedSeen(userId);
        return Result.ok();
    }

    @Operation(summary = "动态评论列表")
    @GetMapping("/{postId}/comments")
    public Result<MomentCommentListResponse> listComments(
            @PathVariable("postId") Long postId,
            @RequestParam(value = "cursor", required = false) Long cursor,
            @RequestParam(value = "limit", defaultValue = "50") int limit
    ) {
        long userId = StpUtil.getLoginIdAsLong();
        return Result.ok(momentsCommentService.listComments(userId, postId, cursor, limit));
    }

    @Operation(summary = "发表动态评论")
    @PostMapping("/{postId}/comments")
    public Result<MomentCommentResponse> addComment(
            @PathVariable("postId") Long postId,
            @RequestBody CreateMomentCommentRequest request
    ) {
        long userId = StpUtil.getLoginIdAsLong();
        return Result.ok(momentsCommentService.addUserComment(userId, postId, request));
    }
}
