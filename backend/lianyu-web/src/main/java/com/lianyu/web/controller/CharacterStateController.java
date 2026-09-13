package com.lianyu.web.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.lianyu.common.base.Result;
import com.lianyu.service.character.CharacterStateQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Tag(name = "CharacterState", description = "角色情绪与日记")
@RestController
@RequestMapping("/api/character-state")
@RequiredArgsConstructor
public class CharacterStateController {

    private final CharacterStateQueryService characterStateQueryService;

    @Operation(summary = "获取角色当前情绪状态")
    @GetMapping("/{id}/state")
    public Result<Map<String, Object>> getState(@PathVariable("id") Long characterId) {
        long userId = StpUtil.getLoginIdAsLong();
        return characterStateQueryService.getState(userId, characterId);
    }

    @Operation(summary = "批量获取用户所有角色的情绪状态")
    @GetMapping("/states")
    public Result<List<Map<String, Object>>> listStates() {
        long userId = StpUtil.getLoginIdAsLong();
        return characterStateQueryService.listStates(userId);
    }

    @Operation(summary = "获取角色日记列表")
    @GetMapping("/{id}/diary")
    public Result<List<Map<String, Object>>> listDiaries(
            @PathVariable("id") Long characterId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        long userId = StpUtil.getLoginIdAsLong();
        return characterStateQueryService.listDiaries(userId, characterId, page, size);
    }

    @Operation(summary = "获取单篇日记详情")
    @GetMapping("/diary/{diaryId}")
    public Result<Map<String, Object>> getDiary(@PathVariable("diaryId") Long diaryId) {
        long userId = StpUtil.getLoginIdAsLong();
        return characterStateQueryService.getDiary(userId, diaryId);
    }

    @Operation(summary = "获取所有角色的日记（全量，按时间倒序）")
    @GetMapping("/diaries")
    public Result<List<Map<String, Object>>> listAllDiaries(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        long userId = StpUtil.getLoginIdAsLong();
        return characterStateQueryService.listAllDiaries(userId, page, size);
    }
}
