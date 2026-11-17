package com.lianyu.service.qq;

import com.lianyu.service.qq.QqBotConnectService.InternalAccount;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;

/**
 * 把予念的主动问候送到 QQ 上。
 *
 * <p>为什么由 Java 直发、不经过网关：主动消息是「予念决定要说一句话」，而网关的职责是
 * 「腾讯说有人说话」——方向相反。凭据、openid、额度都在 Java 这一侧，多绕一跳只会让
 * 网关多一份发消息的权限，没有收益。
 *
 * <p>三条硬规矩：
 * <ol>
 *   <li><b>只走用户自己的 bot</b>：{@code qq_bot_account} 里没有就什么都不发，绝不借用别人的。</li>
 *   <li><b>只走绑定了同一个角色的 bot</b>：小满的问候不能用甘雨的机器人发出去。</li>
 *   <li><b>自己先设上限</b>：腾讯给小单聊每天 1000 条，但用户会先烦。默认每天 3 条，
 *       且和「予念主动问候」共用同一次触发——不是另起一套频率。</li>
 * </ol>
 *
 * <p>它永远不该让主流程失败：任何异常都吞掉只记日志，主动消息是锦上添花，
 * 发送失败不能连带一次正常的主动问候也回滚。
 */
@Slf4j
@Service
public class QqProactivePushService {

    private static final String LEDGER_PREFIX = "qq:proactive:";
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");
    /** 腾讯的文本上限很宽，但主动消息是「打扰」，太长的句子在手机通知里也看不全。 */
    private static final int MAX_CHARS = 300;

    private final QqBotConnectService connectService;
    private final QqBotConnectClient connectClient;
    private final StringRedisTemplate redisTemplate;

    @Value("${lianyu.qq.proactive.enabled:true}")
    private boolean enabled;

    /**
     * 保险丝，不是产品频率：真正的「多久主动说一次」由角色自己的主动消息设置与
     * {@code ProactiveChatScheduler} 决定，这里只兜住程序跑飞（例如死循环里反复推）。
     * 腾讯给的是 1000 条/用户/天，我们离它很远。
     */
    @Value("${lianyu.qq.proactive.daily-cap:10}")
    private int dailyCap;

    public QqProactivePushService(QqBotConnectService connectService,
                                  QqBotConnectClient connectClient,
                                  StringRedisTemplate redisTemplate) {
        this.connectService = connectService;
        this.connectClient = connectClient;
        this.redisTemplate = redisTemplate;
    }

    /**
     * 予念主动发了一条消息后调用；成功送到 QQ 返回 true。
     *
     * @param characterId 只有绑定了这个角色的 bot 才会被使用
     * @param content     角色已经说出口的那句台词
     */
    public boolean push(Long userId, Long characterId, String content) {
        if (!enabled || userId == null || characterId == null) {
            return false;
        }
        String text = normalize(content);
        if (text == null) {
            return false;
        }
        try {
            List<InternalAccount> accounts = connectService.activeAccountsForUser(userId).stream()
                    .filter(a -> Objects.equals(a.characterId(), characterId))
                    .toList();
            if (accounts.isEmpty()) {
                log.debug("QQ 主动消息跳过：用户 {} 没有绑定角色 {} 的机器人", userId, characterId);
                return false;
            }
            String key = ledgerKey(userId);
            long sent = currentCount(key);
            if (sent >= dailyCap) {
                log.info("QQ 主动消息跳过：今日已发 {} 条（上限 {}），userId={}", sent, dailyCap, userId);
                return false;
            }
            InternalAccount account = accounts.get(0);
            String token = connectService.issueAccessToken(account.appId()).accessToken();
            String messageId = connectClient.sendProactiveC2C(token, account.externalOpenid(), text);
            long after = increment(key);
            log.info("QQ 主动消息已投递 userId={} appId={} msgId={} 今日第 {} 条 '{}{}'",
                    userId, account.appId(), messageId, after,
                    text.length() > 20 ? text.substring(0, 20) : text,
                    text.length() > 20 ? "…" : "");
            return true;
        } catch (RuntimeException e) {
            // 认证类错误要让用户在页面上看见（清掉 token 才有机会恢复），其余只记日志。
            log.warn("QQ 主动消息发送失败 userId={} characterId={}：{}", userId, characterId, e.getMessage());
            if (looksLikeAuthFailure(e.getMessage())) {
                connectService.activeAccountsForUser(userId).stream()
                        .filter(a -> Objects.equals(a.characterId(), characterId))
                        .findFirst()
                        .ifPresent(a -> connectService.markError(a.appId(), abbreviate(e.getMessage())));
            }
            return false;
        }
    }

    /** 今天已给这个人发了多少条（排查用，也给页面将来显示额度留口子）。 */
    public long todayCount(Long userId) {
        if (userId == null) {
            return 0;
        }
        return currentCount(ledgerKey(userId));
    }

    private String ledgerKey(Long userId) {
        return LEDGER_PREFIX + userId + ":" + LocalDate.now(ZONE).format(DAY);
    }

    private long currentCount(String key) {
        try {
            String raw = redisTemplate.opsForValue().get(key);
            return raw == null ? 0 : Long.parseLong(raw);
        } catch (RuntimeException e) {
            // 账本读不出来不该挡住发送，退化成「当作 0 条」。
            log.debug("读取 QQ 主动消息账本失败：{}", e.getMessage());
            return 0;
        }
    }

    private long increment(String key) {
        try {
            Long value = redisTemplate.opsForValue().increment(key);
            if (value != null && value == 1L) {
                // 自然日账本，多留一天余量，跨日后自然归零。
                redisTemplate.expire(key, Duration.ofHours(26));
            }
            return value == null ? 0 : value;
        } catch (RuntimeException e) {
            log.debug("写入 QQ 主动消息账本失败：{}", e.getMessage());
            return 0;
        }
    }

    private static String normalize(String content) {
        if (content == null) {
            return null;
        }
        String text = content.strip();
        if (text.isEmpty()) {
            return null;
        }
        return text.length() > MAX_CHARS ? text.substring(0, MAX_CHARS) : text;
    }

    private static boolean looksLikeAuthFailure(String message) {
        if (message == null) {
            return false;
        }
        return message.contains("401") || message.contains("403") || message.contains("无效")
                || message.toLowerCase().contains("token");
    }

    private static String abbreviate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() > 200 ? message.substring(0, 200) : message;
    }
}
