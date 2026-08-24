package com.lianyu.admin.identity;

import jakarta.validation.constraints.NotBlank;

public final class AdminDtos {
    private AdminDtos() {}
    public record LoginRequest(@NotBlank String username, @NotBlank String password, String otp) {}
    public record LoginResponse(String accessToken, String username, boolean otpRequired, String traceId) {}
    public record TotpSetupResponse(String secret, String otpauthUri) {}
    public record TotpCodeRequest(@NotBlank String code) {}
    public record AdminCreateRequest(@NotBlank String username, @NotBlank String displayName, @NotBlank String password, String roleKey) {}
    public record RoleAssignmentRequest(@NotBlank String roleKey) {}
}
