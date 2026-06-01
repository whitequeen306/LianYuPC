package com.lianyu.service.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class LoginResponse {
    private String token;
    private String tokenName;
    private Long userId;
    private String username;
    private String nickname;
    private String avatarUrl;
}
