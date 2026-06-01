package com.lianyu.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import com.lianyu.common.base.ErrorCode;
import com.lianyu.common.exception.BusinessException;
import com.lianyu.dao.entity.User;
import com.lianyu.dao.mapper.UserMapper;
import com.lianyu.service.AuthService;
import com.lianyu.service.FileStorageService;
import com.lianyu.service.dto.LoginRequest;
import com.lianyu.service.dto.LoginResponse;
import com.lianyu.service.dto.RegisterRequest;
import com.lianyu.service.dto.UpdateProfileRequest;
import com.lianyu.service.dto.UserProfile;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final FileStorageService fileStorageService;

    @Override
    @Transactional
    public void register(RegisterRequest request) {
        boolean exists = userMapper.exists(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, request.getUsername()));
        if (exists) {
            throw new BusinessException(ErrorCode.USERNAME_EXISTS);
        }

        User user = new User();
        user.setUsername(request.getUsername());
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        user.setNickname(request.getNickname() != null ? request.getNickname() : request.getUsername());
        userMapper.insert(user);
        log.info("User registered: {}", user.getUsername());
    }

    @Override
    public LoginResponse login(LoginRequest request) {
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, request.getUsername()));
        if (user == null) {
            throw new BusinessException(ErrorCode.WRONG_PASSWORD);
        }
        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.WRONG_PASSWORD);
        }

        StpUtil.login(user.getId());
        String token = StpUtil.getTokenValue();

        log.info("User logged in: {}", user.getUsername());
        return LoginResponse.builder()
                .token(token)
                .tokenName(StpUtil.getTokenName())
                .userId(user.getId())
                .username(user.getUsername())
                .nickname(user.getNickname())
                .avatarUrl(user.getAvatarUrl())
                .build();
    }

    @Override
    public void logout() {
        StpUtil.logout();
    }

    @Override
    public UserProfile me(Long userId) {
        return toProfile(findUser(userId));
    }

    @Override
    @Transactional
    public UserProfile updateProfile(Long userId, UpdateProfileRequest request) {
        User user = findUser(userId);
        user.setNickname(request.getNickname().trim());
        userMapper.updateById(user);
        return toProfile(user);
    }

    @Override
    @Transactional
    public UserProfile uploadAvatar(Long userId, MultipartFile file) {
        User user = findUser(userId);
        user.setAvatarUrl(fileStorageService.uploadAvatar(file));
        userMapper.updateById(user);
        return toProfile(user);
    }

    private User findUser(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }
        return user;
    }

    private UserProfile toProfile(User user) {
        return UserProfile.builder()
                .userId(user.getId())
                .username(user.getUsername())
                .nickname(user.getNickname())
                .avatarUrl(user.getAvatarUrl())
                .build();
    }
}
