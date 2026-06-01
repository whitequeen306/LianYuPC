package com.lianyu.service;

import com.lianyu.common.base.ErrorCode;
import com.lianyu.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@RequiredArgsConstructor
public class AuthRateLimiter {

    private static final String IP_PREFIX = "auth:rate:ip:";
    private static final String USER_PREFIX = "auth:rate:user:";

    private final StringRedisTemplate redisTemplate;

    @Value("${lianyu.auth.rate-limit.per-ip-per-minute:30}")
    private int perIpPerMinute;

    @Value("${lianyu.auth.rate-limit.per-username-per-minute:10}")
    private int perUsernamePerMinute;

    public void checkLoginOrRegister(String clientIp, String username) {
        if (clientIp != null && !clientIp.isBlank()) {
            incrementOrThrow(IP_PREFIX + clientIp.trim(), perIpPerMinute,
                    Duration.ofMinutes(1), "请求过于频繁，请稍后再试");
        }
        if (username != null && !username.isBlank()) {
            incrementOrThrow(USER_PREFIX + username.trim().toLowerCase(), perUsernamePerMinute,
                    Duration.ofMinutes(1), "该账号尝试次数过多，请稍后再试");
        }
    }

    private void incrementOrThrow(String key, int max, Duration ttl, String message) {
        Long count = redisTemplate.opsForValue().increment(key);
        if (count != null && count == 1L) {
            redisTemplate.expire(key, ttl);
        }
        if (count != null && count > max) {
            throw new BusinessException(ErrorCode.AUTH_RATE_LIMITED, message);
        }
    }
}
