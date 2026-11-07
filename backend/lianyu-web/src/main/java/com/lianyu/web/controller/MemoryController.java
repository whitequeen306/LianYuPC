package com.lianyu.web.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.lianyu.common.base.Result;
import com.lianyu.service.memory.MemoryDecayService;
import com.lianyu.service.memory.MemoryQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Tag(name = "Memory", description = "记忆管理")
@RestController
@RequestMapping("/api/memory")
@RequiredArgsConstructor
public class MemoryController {

    private final MemoryQueryService memoryQueryService;
    private final MemoryDecayService memoryDecayService;

    @Operation(summary = "记忆列表（按角色分组）")
    @GetMapping
    public Result<List<Map<String, Object>>> list(
            @RequestParam(required = false) Long characterId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int size) {
        long userId = StpUtil.getLoginIdAsLong();
        return memoryQueryService.list(userId, characterId, page, size);
    }

    @Operation(summary = "共同经历列表（原话 + 发生时间）")
    @GetMapping("/episodes")
    public Result<List<Map<String, Object>>> episodes(
            @RequestParam(required = false) Long characterId,
            @RequestParam(defaultValue = "50") int limit) {
        long userId = StpUtil.getLoginIdAsLong();
        return memoryQueryService.episodes(userId, characterId, limit);
    }

    @Operation(summary = "获取记忆详情（含来源消息）")
    @GetMapping("/{id}")
    public Result<Map<String, Object>> detail(@PathVariable Long id) {
        long userId = StpUtil.getLoginIdAsLong();
        return memoryQueryService.detail(userId, id);
    }

    @Operation(summary = "钉住/取消钉住记忆（钉住即免衰减）")
    @PatchMapping("/{id}/pin")
    public Result<Void> pin(@PathVariable Long id, @RequestBody PinRequest request) {
        long userId = StpUtil.getLoginIdAsLong();
        return memoryQueryService.setPinned(userId, id, request.pinned());
    }

    public record PinRequest(boolean pinned) {
    }

    @Operation(summary = "不再主动提起/恢复提起记忆")
    @PatchMapping("/{id}/mute")
    public Result<Void> mute(@PathVariable Long id, @RequestBody MuteRequest request) {
        long userId = StpUtil.getLoginIdAsLong();
        return memoryQueryService.setMuted(userId, id, request.muted());
    }

    public record MuteRequest(boolean muted) {
    }

    @Operation(summary = "让她想起来（把沉底的记忆拉回来）")
    @PostMapping("/{id}/revive")
    public Result<Void> revive(@PathVariable Long id) {
        long userId = StpUtil.getLoginIdAsLong();
        return memoryQueryService.revive(userId, id);
    }

    @Operation(summary = "删除记忆")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        long userId = StpUtil.getLoginIdAsLong();
        return memoryQueryService.delete(userId, id);
    }

    @Operation(summary = "立即结算一次记忆衰减（运维/验证用）")
    @PostMapping("/decay/settle")
    public Result<Map<String, Object>> settleDecay() {
        // 定时任务凌晨 3:30 才跑，且要求 last_decay_at 早于 7 天前；
        // 上线后想确认「衰减到底会不会动」只能手动跑一次，否则要等到下周。
        StpUtil.checkLogin();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("settled", memoryDecayService.settle(LocalDateTime.now()));
        return Result.ok(body);
    }
}
