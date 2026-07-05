package com.lianyu.service.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import jakarta.validation.Valid;
import lombok.Data;

@Data
public class RegisterRequest {
    @NotBlank @Size(min = 2, max = 64)
    private String username;
    @NotBlank @Size(min = 6, max = 128)
    @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).{6,}$",
             message = "密码需至少6位，且必须同时包含字母和数字")
    private String password;
    @Size(max = 128)
    private String nickname;
    @Valid
    private CaptchaVerifyRequest captcha;
}
