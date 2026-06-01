package com.lianyu.service;

import com.lianyu.common.base.ErrorCode;
import com.lianyu.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 限制通过 /api/ai/chat 直连消耗平台 Key 的次数。
 */
@Service
@RequiredArgsConstructor
public class AiChatQuotaService {

    private static final String KEY_PREFIX = "ai:direct:daily:";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final StringRedisTemplate redisTemplate;

    @Value("${lianyu.ai.direct-chat-enabled:false}")
    private boolean directChatEnabled;

    @Value("${lianyu.ai.direct-chat-daily-limit:50}")
    private int directChatDailyLimit;

    public void assertDirectChatAllowed(long userId) {
        if (!directChatEnabled) {
            throw new BusinessException(ErrorCode.FORBIDDEN,
                    "直连 AI 对话接口已关闭，请通过会话页发送消息");
        }
        String key = KEY_PREFIX + LocalDate.now().format(DAY) + ":" + userId;
        Long count = redisTemplate.opsForValue().increment(key);
        if (count != null && count == 1L) {
            redisTemplate.expire(key, java.time.Duration.ofDays(2));
        }
        if (count != null && count > directChatDailyLimit) {
            throw new BusinessException(ErrorCode.AI_RATE_LIMITED,
                    "今日直连 AI 次数已达上限（" + directChatDailyLimit + "）");
        }
    }
}
