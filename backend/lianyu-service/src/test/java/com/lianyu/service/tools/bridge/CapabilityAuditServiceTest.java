package com.lianyu.service.tools.bridge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

/** 审计：摘要不可逆、确认解析、写库失败不影响执行、开关生效。 */
@ExtendWith(MockitoExtension.class)
class CapabilityAuditServiceTest {

    @Mock
    private JdbcTemplate jdbc;

    private CapabilityAuditService service;

    @BeforeEach
    void setUp() {
        service = new CapabilityAuditService(jdbc);
        ReflectionTestUtils.setField(service, "enabled", true);
    }

    private CapabilityAuditService.AuditEntry entry() {
        return new CapabilityAuditService.AuditEntry(
                7L, "desk-7-abcd1234", "desk-7-abcd1234#3", "desk_click",
                CapabilityAuditService.digestArgs("{\"label\":\"播放\"}"),
                "R2", "user_request", true, true, "", 123L);
    }

    @Test
    void digestIsStableShortAndNeverPlaintext() {
        String digest = CapabilityAuditService.digestArgs("{\"text\":\"我的密码是 xxx\"}");
        assertThat(digest).hasSize(16).matches("[0-9a-f]{16}");
        assertThat(digest).isEqualTo(CapabilityAuditService.digestArgs("{\"text\":\"我的密码是 xxx\"}"));
        assertThat(digest).doesNotContain("密码");
        assertThat(CapabilityAuditService.digestArgs("")).isEmpty();
        assertThat(CapabilityAuditService.digestArgs(null)).isEmpty();
    }

    @Test
    void confirmedFlagIsParsedFromClientPolicy() {
        assertThat(CapabilityAuditService.confirmedFrom(Map.of("confirmed", true))).isTrue();
        assertThat(CapabilityAuditService.confirmedFrom(Map.of("confirmed", "true"))).isTrue();
        assertThat(CapabilityAuditService.confirmedFrom(Map.of("confirmed", false))).isFalse();
        assertThat(CapabilityAuditService.confirmedFrom(Map.of())).isFalse();
        assertThat(CapabilityAuditService.confirmedFrom(null)).isFalse();
    }

    @Test
    void recordWritesOneRow() {
        service.record(entry());
        verify(jdbc).update(contains("agent_capability_audit"), any(Object[].class));
    }

    @Test
    void recordSkipsWhenDisabled() {
        ReflectionTestUtils.setField(service, "enabled", false);
        service.record(entry());
        verify(jdbc, never()).update(any(String.class), any(Object[].class));
    }

    @Test
    void recordSwallowsDatabaseFailures() {
        when(jdbc.update(contains("agent_capability_audit"), any(Object[].class)))
                .thenThrow(new RuntimeException("table missing"));
        service.record(entry());   // 不抛：审计失败不能阻断能力
        verify(jdbc).update(contains("agent_capability_audit"), any(Object[].class));
    }

    @Test
    void recordIgnoresMissingUser() {
        CapabilityAuditService.AuditEntry noUser = new CapabilityAuditService.AuditEntry(
                null, "s", "s#1", "desk_click", "", "R2", "user_request", false, true, "", 1L);
        service.record(noUser);
        verify(jdbc, never()).update(any(String.class), any(Object[].class));
    }
}
