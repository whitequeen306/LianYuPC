package com.lianyu.admin.identity;

import com.lianyu.common.base.Result;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/v1/auth")
@RequiredArgsConstructor
public class AdminAuthController {
    private final AdminAuthService authService;
    @PostMapping("/login") public Result<AdminDtos.LoginResponse> login(@Valid @RequestBody AdminDtos.LoginRequest request) { return Result.ok(authService.login(request)); }
    @PostMapping("/logout") public Result<Void> logout() { authService.logout(); return Result.ok(); }
    @GetMapping("/me") public Result<java.util.Map<String,Object>> me() { return Result.ok(java.util.Map.of("username", authService.currentUsername())); }
}
