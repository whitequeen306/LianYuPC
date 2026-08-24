package com.lianyu.admin.release;

import com.lianyu.common.base.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/public/admin-release")
@RequiredArgsConstructor
public class ReleaseResolverController {
    private final ReleaseResolverService service;
    @GetMapping public Result<Map<String,Object>> resolve(@RequestParam(defaultValue="stable") String channel, @RequestParam(required=false) String subject) { return Result.ok(service.resolve(channel, subject)); }
}
