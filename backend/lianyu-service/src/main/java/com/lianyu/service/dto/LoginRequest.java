package com.lianyu.service.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class LoginRequest {
    @NotBlank
    private String username;
    @NotBlank
    private String password;
    @Valid
    private CaptchaVerifyRequest captcha;
}