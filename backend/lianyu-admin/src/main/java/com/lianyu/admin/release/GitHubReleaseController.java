package com.lianyu.admin.release;

import com.lianyu.admin.identity.AdminAuthorizationService;
import com.lianyu.common.base.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController @RequestMapping("/api/admin/v1/releases/github") @RequiredArgsConstructor
public class GitHubReleaseController {
    private final AdminAuthorizationService authorization; private final GitHubReleaseImporter importer;
    @PostMapping("/inspect") public Result<Map<String,Object>> inspect(@RequestBody Map<String,String> body) { authorization.require("release.manage"); return Result.ok(importer.inspect(body.get("apiUrl"),body.get("repository"),body.get("tag"))); }
}
