package com.lianyu.admin.support;

import com.lianyu.common.base.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController @RequestMapping("/api/admin/v1/support/grants") @RequiredArgsConstructor
public class SupportGrantController {
    private final SupportGrantService service;
    @PostMapping("/{conversationId}") public Result<Map<String,String>> issue(@PathVariable long conversationId){return Result.ok(Map.of("code",service.issue(conversationId)));}
    @PostMapping("/{conversationId}/redeem") public Result<Void> redeem(@PathVariable long conversationId,@RequestBody Map<String,String> body){service.redeem(conversationId,body.get("code"));return Result.ok();}
}
