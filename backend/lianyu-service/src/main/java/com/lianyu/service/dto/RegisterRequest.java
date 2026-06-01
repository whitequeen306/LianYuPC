package com.lianyu.service.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class RegisterRequest {
    @NotBlank @Size(min = 2, max = 64)
    private String username;
    @NotBlank @Size(min = 6, max = 128)
    private String password;
    @Size(max = 128)
    private String nickname;
    @Valid
    private CaptchaVerifyRequest captcha;
}