package com.lianyu.web.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.lianyu.common.base.Result;
import com.lianyu.service.community.CommunityService;
import com.lianyu.service.dto.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@Tag(name = "Community", description = "用户社区")
@RestController
@RequestMapping("/api/community")
@RequiredArgsConstructor
public class CommunityController {

    private final CommunityService communityService;

    @Operation(summary = "社区广场动态流")
    @GetMapping("/feed")
    public Result<CommunityFeedResponse> feed(
            @RequestParam(value = "cursor", required = false) Long cursor,
            @RequestParam(value = "limit", defaultValue = "20") int limit
    ) {
        long userId = StpUtil.getLoginIdAsLong();
        return Result.ok(communityService.listFeed(userId, cursor, limit));
    }

    @Operation(summary = "发表社区动态")
    @PostMapping("/posts")
    public Result<CommunityPostResponse> createPost(@Valid @RequestBody CreateCommunityPostRequest request) {
        long userId = StpUtil.getLoginIdAsLong();
        return Result.ok(communityService.createPost(userId, request));
    }

    @Operation(summary = "删除社区动态（软删）")
    @DeleteMapping("/posts/{id}")
    public Result<Void> deletePost(@PathVariable("id") Long id) {
        long userId = StpUtil.getLoginIdAsLong();
        communityService.deletePost(userId, id);
        return Result.ok();
    }

    @Operation(summary = "点赞/取消点赞")
    @PostMapping("/posts/{id}/like")
    public Result<CommunityLikeResponse> toggleLike(@PathVariable("id") Long id) {
        long userId = StpUtil.getLoginIdAsLong();
        return Result.ok(communityService.toggleLike(userId, id));
    }

    @Operation(summary = "评论列表")
    @GetMapping("/posts/{id}/comments")
    public Result<CommunityCommentListResponse> listComments(
            @PathVariable("id") Long id,
            @RequestParam(value = "cursor", required = false) Long cursor,
            @RequestParam(value = "limit", defaultValue = "50") int limit
    ) {
        long userId = StpUtil.getLoginIdAsLong();
        return Result.ok(communityService.listComments(userId, id, cursor, limit));
    }

    @Operation(summary = "发表评论")
    @PostMapping("/posts/{id}/comments")
    public Result<CommunityCommentResponse> addComment(
            @PathVariable("id") Long id,
            @Valid @RequestBody CreateCommunityCommentRequest request
    ) {
        long userId = StpUtil.getLoginIdAsLong();
        return Result.ok(communityService.addComment(userId, id, request));
    }

    @Operation(summary = "上传社区配图")
    @PostMapping("/images")
    public Result<Map<String, String>> uploadImage(@RequestParam("file") MultipartFile file) {
        String imageUrl = communityService.uploadImage(file);
        return Result.ok(Map.of("imageUrl", imageUrl));
    }
}
