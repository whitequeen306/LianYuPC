package com.lianyu.admin.identity;

import jakarta.validation.constraints.NotBlank;

public final class AdminDtos {
    private AdminDtos() {}
    public record LoginRequest(@NotBlank String username, @NotBlank String password, String otp) {}
    public record LoginResponse(String accessToken, String username, boolean otpRequired) {}
}
