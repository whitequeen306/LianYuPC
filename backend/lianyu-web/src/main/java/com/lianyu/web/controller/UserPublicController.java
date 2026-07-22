package com.lianyu.web.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.lianyu.common.base.Result;
import com.lianyu.service.community.CommunityService;
import com.lianyu.service.dto.CommunityFeedResponse;
import com.lianyu.service.dto.PublicUserProfileResponse;
import com.lianyu.service.user.UserPublicProfileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Users", description = "用户公开主页")
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserPublicController {

    private final UserPublicProfileService userPublicProfileService;
    private final CommunityService communityService;

    @Operation(summary = "公开个人资料（含角色橱窗）")
    @GetMapping("/{id}/profile")
    public Result<PublicUserProfileResponse> profile(@PathVariable("id") Long id) {
        long viewerId = StpUtil.getLoginIdAsLong();
        return Result.ok(userPublicProfileService.getPublicProfile(viewerId, id));
    }

    @Operation(summary = "用户主页社区动态")
    @GetMapping("/{id}/community-posts")
    public Result<CommunityFeedResponse> communityPosts(
            @PathVariable("id") Long id,
            @RequestParam(value = "cursor", required = false) Long cursor,
            @RequestParam(value = "limit", defaultValue = "5") int limit
    ) {
        long viewerId = StpUtil.getLoginIdAsLong();
        return Result.ok(communityService.listUserPosts(viewerId, id, cursor, limit));
    }
}
