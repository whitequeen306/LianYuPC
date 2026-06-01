package com.lianyu.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;

/**
 * 登录/注册验证码服务。
 * <p>
 * 生成随机算式（加减乘除），答案存 Redis，TTL 2 分钟。
 * 每次调用 {@link #generate()} 生成新验证码，同一 session 可多次刷新。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CaptchaService {

    private final StringRedisTemplate redisTemplate;

    private static final String CAPTCHA_PREFIX = "captcha:";
    private static final Duration CAPTCHA_TTL = Duration.ofMinutes(2);
    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * 生成一个算式验证码。
     * @return 包含 id 和算式描述（如 "8 + 3 = ?"）的 DTO
     */
    public CaptchaChallenge generate() {
        int a, b, answer;
        String operator;
        int op = RANDOM.nextInt(4); // 0=add, 1=sub, 2=mul, 3=simple div

        switch (op) {
            case 0 -> { // 加法: 1~99 + 1~99
                a = RANDOM.nextInt(1, 100);
                b = RANDOM.nextInt(1, 100);
                answer = a + b;
                operator = "+";
            }
            case 1 -> { // 减法: 保证结果 ≥ 0
                a = RANDOM.nextInt(10, 100);
                b = RANDOM.nextInt(1, a + 1);
                answer = a - b;
                operator = "-";
            }
            case 2 -> { // 乘法: 2~9 × 1~9
                a = RANDOM.nextInt(2, 10);
                b = RANDOM.nextInt(1, 10);
                answer = a * b;
                operator = "×";
            }
            default -> { // 简单除法: 能整除
                b = RANDOM.nextInt(2, 10);
                int quotient = RANDOM.nextInt(1, 10);
                a = b * quotient;
                answer = quotient;
                operator = "÷";
            }
        }

        String id = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String key = CAPTCHA_PREFIX + id;
        redisTemplate.opsForValue().set(key, String.valueOf(answer), CAPTCHA_TTL);

        log.debug("Captcha generated: id={}, {} {} {} = {}", id, a, operator, b, answer);
        return new CaptchaChallenge(id, a + " " + operator + " " + b + " = ?");
    }

    /**
     * 校验验证码答案。每个验证码只能校验一次（用后即焚）。
     * @return true 表示验证通过
     */
    public boolean verify(String captchaId, int userAnswer) {
        if (captchaId == null || captchaId.isBlank()) {
            return false;
        }
        // 安全起见，拒绝含非字母数字的 id
        if (!captchaId.matches("[a-zA-Z0-9]+")) {
            return false;
        }
        String key = CAPTCHA_PREFIX + captchaId;
        String stored = redisTemplate.opsForValue().getAndDelete(key);
        if (stored == null) {
            log.debug("Captcha verify failed: not found or expired, id={}", captchaId);
            return false;
        }
        try {
            int expected = Integer.parseInt(stored);
            boolean ok = expected == userAnswer;
            log.debug("Captcha verify: id={}, expected={}, got={}, result={}", captchaId, expected, userAnswer, ok);
            return ok;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** 算式描述（前端展示） */
    public record CaptchaChallenge(String id, String expression) {}
}