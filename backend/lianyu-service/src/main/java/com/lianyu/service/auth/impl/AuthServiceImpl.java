package com.lianyu.service.auth.impl;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lianyu.common.base.ErrorCode;
import com.lianyu.common.exception.BusinessException;
import com.lianyu.dao.entity.User;
import com.lianyu.dao.mapper.UserMapper;
import com.lianyu.service.auth.AuthService;
import com.lianyu.service.dto.ChangePasswordRequest;
import com.lianyu.service.dto.LoginRequest;
import com.lianyu.service.dto.LoginResponse;
import com.lianyu.service.dto.RegisterRequest;
import com.lianyu.service.dto.UpdateProfileRequest;
import com.lianyu.service.dto.UserProfile;
import com.lianyu.service.storage.FileStorageService;
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
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
    public LoginResponse register(RegisterRequest request) {
        User existing = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, request.getUsername()));
        if (existing != null) {
            if (!passwordEncoder.matches(request.getPassword(), existing.getPasswordHash())) {
                throw new BusinessException(ErrorCode.USERNAME_EXISTS);
            }
            // 上次注册可能已在服务端成功，但客户端未收到响应；密码一致则直接登录
            log.info("Register idempotent login: username={}", existing.getUsername());
            StpUtil.login(existing.getId());
            return toLoginResponse(existing);
        }

        User user = new User();
        user.setUsername(request.getUsername());
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        user.setNickname(resolveNickname(request.getNickname()));
        userMapper.insert(user);
        log.info("User registered: {}", user.getUsername());

        StpUtil.login(user.getId());
        return toLoginResponse(user);
    }

    @Override
    public LoginResponse login(LoginRequest request) {
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, request.getUsername()));
        if (user == null) {
            throw new BusinessException(ErrorCode.ACCOUNT_NOT_REGISTERED);
        }
        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.WRONG_PASSWORD);
        }

        StpUtil.login(user.getId());
        log.info("User logged in: {}", user.getUsername());
        return toLoginResponse(user);
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

    @Override
    @Transactional
    public void changePassword(Long userId, ChangePasswordRequest request) {
        User user = findUser(userId);
        if (!passwordEncoder.matches(request.getOldPassword(), user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.WRONG_PASSWORD);
        }
        user.setPasswordHash(passwordEncoder.encode(request.getNewPassword()));
        userMapper.updateById(user);
        log.info("User password changed: {}", user.getUsername());
    }

    private User findUser(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }
        return user;
    }

    private String resolveNickname(String nickname) {
        if (StrUtil.isNotBlank(nickname)) {
            return nickname.trim();
        }
        return "恋语用户_" + RandomUtil.randomNumbers(4) + RandomUtil.randomString(RandomUtil.BASE_CHAR, 4);
    }

    private UserProfile toProfile(User user) {
        return UserProfile.builder()
                .userId(user.getId())
                .username(user.getUsername())
                .nickname(user.getNickname())
                .avatarUrl(fileStorageService.resolvePublicUrl(user.getAvatarUrl()))
                .build();
    }

    private LoginResponse toLoginResponse(User user) {
        return LoginResponse.builder()
                .token(StpUtil.getTokenValue())
                .tokenName(StpUtil.getTokenName())
                .userId(user.getId())
                .username(user.getUsername())
                .nickname(user.getNickname())
                .avatarUrl(fileStorageService.resolvePublicUrl(user.getAvatarUrl()))
                .build();
    }
}
