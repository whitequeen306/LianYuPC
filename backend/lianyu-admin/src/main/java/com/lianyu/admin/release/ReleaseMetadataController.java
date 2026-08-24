package com.lianyu.admin.release;

import com.lianyu.admin.identity.AdminAuthorizationService;
import com.lianyu.common.base.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/admin/v1/releases/metadata")
@RequiredArgsConstructor
public class ReleaseMetadataController {
    private final AdminAuthorizationService authorization;
    private final ReleaseValidationService validation;
    @PostMapping("/validate")
    public Result<Void> validate(@RequestBody Map<String,Object> body) {
        authorization.require("release.manage");
        validation.validatePackage(String.valueOf(body.get("fileName")), ((Number) body.getOrDefault("size", 0)).longValue());
        validation.validateSha512(String.valueOf(body.get("sha512")));
        validation.normalizeVersion(String.valueOf(body.get("version")));
        validation.validateChannel(String.valueOf(body.getOrDefault("channel", "stable")));
        return Result.ok();
    }
}
