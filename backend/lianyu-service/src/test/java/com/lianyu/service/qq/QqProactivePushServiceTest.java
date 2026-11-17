package com.lianyu.service.qq;

import com.lianyu.service.qq.QqBotConnectService.InternalAccount;
import com.lianyu.service.qq.QqBotConnectService.TokenView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 主动消息：能不能发、发给谁、发几条、发挂了会不会带崩主流程。
 *
 * <p>这些用例锁的都是线上真踩过的坑：借用别人的 bot、用错角色的 bot、额度失控、
 * 发送失败把主动问候整条回滚。
 */
class QqProactivePushServiceTest {

    private static final Long USER = 421L;
    private static final Long CHARACTER = 1002L;

    private QqBotConnectService connectService;
    private QqBotConnectClient connectClient;
    private StringRedisTemplate redis;
    private ValueOperations<String, String> ops;
    private QqProactivePushService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        connectService = mock(QqBotConnectService.class);
        connectClient = mock(QqBotConnectClient.class);
        redis = mock(StringRedisTemplate.class);
        ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get(anyString())).thenReturn(null);
        when(ops.increment(anyString())).thenReturn(1L);
        service = new QqProactivePushService(connectService, connectClient, redis);
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "dailyCap", 3);
    }

    private void boundAccount(Long characterId) {
        when(connectService.activeAccountsForUser(USER))
                .thenReturn(List.of(new InternalAccount("1906428729", "OPENID-A", characterId)));
        when(connectService.issueAccessToken("1906428729")).thenReturn(new TokenView("TOKEN", 7200));
    }

    @Test
    void sends_the_greeting_through_the_users_own_bot() {
        boundAccount(CHARACTER);
        when(connectClient.sendProactiveC2C("TOKEN", "OPENID-A", "想你了")).thenReturn("MSG-1");

        assertThat(service.push(USER, CHARACTER, "想你了")).isTrue();

        verify(connectClient).sendProactiveC2C("TOKEN", "OPENID-A", "想你了");
        verify(ops).increment(anyString());
    }

    @Test
    void does_nothing_when_the_user_has_no_bot() {
        when(connectService.activeAccountsForUser(USER)).thenReturn(List.of());

        assertThat(service.push(USER, CHARACTER, "想你了")).isFalse();

        verify(connectClient, never()).sendProactiveC2C(anyString(), anyString(), anyString());
    }

    @Test
    void never_borrows_a_bot_bound_to_another_character() {
        boundAccount(1005L);

        assertThat(service.push(USER, CHARACTER, "想你了")).isFalse();

        verify(connectClient, never()).sendProactiveC2C(anyString(), anyString(), anyString());
    }

    @Test
    void stops_at_the_daily_cap() {
        boundAccount(CHARACTER);
        when(ops.get(anyString())).thenReturn("3");

        assertThat(service.push(USER, CHARACTER, "想你了")).isFalse();

        verify(connectClient, never()).sendProactiveC2C(anyString(), anyString(), anyString());
    }

    @Test
    void trims_overlong_text_before_sending() {
        boundAccount(CHARACTER);
        String longText = "啊".repeat(500);
        when(connectClient.sendProactiveC2C(eq("TOKEN"), eq("OPENID-A"), anyString())).thenReturn("MSG-2");

        assertThat(service.push(USER, CHARACTER, longText)).isTrue();

        verify(connectClient).sendProactiveC2C("TOKEN", "OPENID-A", "啊".repeat(300));
    }

    @Test
    void ignores_blank_text() {
        boundAccount(CHARACTER);

        assertThat(service.push(USER, CHARACTER, "   ")).isFalse();

        verify(connectClient, never()).sendProactiveC2C(anyString(), anyString(), anyString());
    }

    @Test
    void failure_never_propagates() {
        boundAccount(CHARACTER);
        when(connectClient.sendProactiveC2C(anyString(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("HTTP 500 from https://api.bot.qq.com"));

        assertThat(service.push(USER, CHARACTER, "想你了")).isFalse();

        verify(ops, never()).increment(anyString());
    }

    @Test
    void auth_failure_is_surfaced_on_the_account() {
        boundAccount(CHARACTER);
        when(connectClient.sendProactiveC2C(anyString(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("HTTP 401 from https://api.bot.qq.com/v2/users/x/messages"));

        assertThat(service.push(USER, CHARACTER, "想你了")).isFalse();

        verify(connectService).markError(eq("1906428729"), any());
    }

    @Test
    void disabled_switch_sends_nothing() {
        boundAccount(CHARACTER);
        ReflectionTestUtils.setField(service, "enabled", false);

        assertThat(service.push(USER, CHARACTER, "想你了")).isFalse();

        verify(connectClient, never()).sendProactiveC2C(anyString(), anyString(), anyString());
    }
}
