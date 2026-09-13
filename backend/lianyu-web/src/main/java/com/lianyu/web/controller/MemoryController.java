package com.lianyu.web.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.lianyu.common.base.Result;
import com.lianyu.service.memory.MemoryQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Tag(name = "Memory", description = "记忆管理")
@RestController
@RequestMapping("/api/memory")
@RequiredArgsConstructor
public class MemoryController {

    private final MemoryQueryService memoryQueryService;

    @Operation(summary = "记忆列表（按角色分组）")
    @GetMapping
    public Result<List<Map<String, Object>>> list(
            @RequestParam(required = false) Long characterId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int size) {
        long userId = StpUtil.getLoginIdAsLong();
        return memoryQueryService.list(userId, characterId, page, size);
    }

    @Operation(summary = "获取记忆详情（含来源消息）")
    @GetMapping("/{id}")
    public Result<Map<String, Object>> detail(@PathVariable Long id) {
        long userId = StpUtil.getLoginIdAsLong();
        return memoryQueryService.detail(userId, id);
    }

    @Operation(summary = "删除记忆")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        long userId = StpUtil.getLoginIdAsLong();
        return memoryQueryService.delete(userId, id);
    }
}
