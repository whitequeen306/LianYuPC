package com.lianyu.service.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

/**
 * 验证码校验请求（随登录/注册一起提交）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CaptchaVerifyRequest {
    @NotBlank(message = "验证码ID不能为空")
    private String captchaId;
    @NotNull(message = "验证码答案不能为空")
    private Integer captchaAnswer;
}