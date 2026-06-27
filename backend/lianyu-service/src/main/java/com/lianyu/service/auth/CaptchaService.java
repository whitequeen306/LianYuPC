package com.lianyu.service.auth;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import javax.imageio.ImageIO;
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 登录/注册验证码服务。
 * <p>
 * 生成随机算式，答案存 Redis；前端仅收到 PNG 图片，不暴露明文算式。
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

    public CaptchaChallenge generate() {
        int a, b, answer;
        String operator;
        int op = RANDOM.nextInt(4);

        switch (op) {
            case 0 -> {
                a = RANDOM.nextInt(1, 100);
                b = RANDOM.nextInt(1, 100);
                answer = a + b;
                operator = "+";
            }
            case 1 -> {
                a = RANDOM.nextInt(10, 100);
                b = RANDOM.nextInt(1, a + 1);
                answer = a - b;
                operator = "-";
            }
            case 2 -> {
                a = RANDOM.nextInt(2, 10);
                b = RANDOM.nextInt(1, 10);
                answer = a * b;
                operator = "×";
            }
            default -> {
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

        String expression = a + " " + operator + " " + b + " = ?";
        String imageBase64 = renderExpressionImage(expression);
        log.debug("Captcha generated: id={}", id);
        return new CaptchaChallenge(id, imageBase64);
    }

    public boolean verify(String captchaId, int userAnswer) {
        if (captchaId == null || captchaId.isBlank()) {
            return false;
        }
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
            log.debug("Captcha verify: id={}, result={}", captchaId, ok);
            return ok;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private String renderExpressionImage(String expression) {
        int width = 180;
        int height = 56;
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(245, 247, 250));
        g.fillRect(0, 0, width, height);
        for (int i = 0; i < 6; i++) {
            g.setColor(new Color(180 + RANDOM.nextInt(50), 180 + RANDOM.nextInt(50), 190 + RANDOM.nextInt(40)));
            int x1 = RANDOM.nextInt(width);
            int y1 = RANDOM.nextInt(height);
            int x2 = RANDOM.nextInt(width);
            int y2 = RANDOM.nextInt(height);
            g.drawLine(x1, y1, x2, y2);
        }
        g.setFont(new Font("SansSerif", Font.BOLD, 22));
        g.setColor(new Color(40, 44, 52));
        int textWidth = g.getFontMetrics().stringWidth(expression);
        g.drawString(expression, Math.max(12, (width - textWidth) / 2), 36);
        for (int i = 0; i < 40; i++) {
            g.setColor(new Color(100 + RANDOM.nextInt(100), 100 + RANDOM.nextInt(100), 110 + RANDOM.nextInt(100)));
            g.fillRect(RANDOM.nextInt(width), RANDOM.nextInt(height), 2, 2);
        }
        g.setStroke(new BasicStroke(1.2f));
        g.dispose();
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", out);
            return Base64.getEncoder().encodeToString(out.toByteArray());
        } catch (Exception e) {
            throw new IllegalStateException("Captcha image render failed", e);
        }
    }

    public record CaptchaChallenge(String id, String imageBase64) {}
}
