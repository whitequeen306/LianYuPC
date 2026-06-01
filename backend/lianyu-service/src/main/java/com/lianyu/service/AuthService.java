package com.lianyu.service;

import com.lianyu.service.dto.LoginRequest;
import com.lianyu.service.dto.LoginResponse;
import com.lianyu.service.dto.RegisterRequest;
import com.lianyu.service.dto.UpdateProfileRequest;
import com.lianyu.service.dto.UserProfile;
import org.springframework.web.multipart.MultipartFile;

public interface AuthService {
    void register(RegisterRequest request);
    LoginResponse login(LoginRequest request);
    void logout();
    UserProfile me(Long userId);
    UserProfile updateProfile(Long userId, UpdateProfileRequest request);
    UserProfile uploadAvatar(Long userId, MultipartFile file);
}
