package com.lianyu.web.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.lianyu.common.base.Result;
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

    @Operation(summary = "公开个人资料（含角色橱窗）")
    @GetMapping("/{id}/profile")
    public Result<PublicUserProfileResponse> profile(@PathVariable("id") Long id) {
        long viewerId = StpUtil.getLoginIdAsLong();
        return Result.ok(userPublicProfileService.getPublicProfile(viewerId, id));
    }
}
