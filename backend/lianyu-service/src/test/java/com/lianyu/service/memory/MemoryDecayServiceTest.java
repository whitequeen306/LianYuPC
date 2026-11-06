package com.lianyu.service.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lianyu.dao.entity.MemoryMeta;
import com.lianyu.dao.mapper.MemoryMetaMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 衰减的数值必须和 Python 读路径一致：同一记忆在两处算出不同的 s_eff，
 * 会出现"检索说她还记得、prompt 里已经模糊"的分裂。
 *
 * <p>这里的期望值抄自 {@code backend-agent/app/memory/retrieve.py} 的注释表：
 * 1 天 ≈ 0.83、30 天 ≈ 0.55、1 年 ≈ 0.18（tau=365）。
 */
class MemoryDecayServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 6, 3, 30);

    @Test
    void effectiveStrengthMatchesPythonCurve() {
        assertEquals(1.0, MemoryDecayService.effectiveStrength(1.0, 365, NOW, NOW), 1e-9);
        assertEquals(0.988, MemoryDecayService.effectiveStrength(1.0, 365, NOW.minusDays(1), NOW), 0.005);
        assertEquals(0.718, MemoryDecayService.effectiveStrength(1.0, 365, NOW.minusDays(30), NOW), 0.01);
        assertEquals(0.203, MemoryDecayService.effectiveStrength(1.0, 365, NOW.minusDays(365), NOW), 0.01);
    }

    @Test
    void pinnedOrCoreNeverDecays() {
        assertEquals(1.0, MemoryDecayService.effectiveStrength(0.3, 0, NOW.minusDays(999), NOW), 1e-9);
    }

    @Test
    void tiersFollowThresholdsAndRecency() {
        assertEquals("active", MemoryDecayService.tierFor(0.7, NOW.minusDays(1), NOW));
        assertEquals("faded", MemoryDecayService.tierFor(0.5, NOW.minusDays(1), NOW));
        assertEquals("archived", MemoryDecayService.tierFor(0.2, NOW.minusDays(1), NOW));
        // 低于 0.15 但最近被想起过：只到 archived，不能判 dormant。
        assertEquals("archived", MemoryDecayService.tierFor(0.1, NOW.minusDays(10), NOW));
        assertEquals("dormant", MemoryDecayService.tierFor(0.1, NOW.minusDays(120), NOW));
        assertEquals("dormant", MemoryDecayService.tierFor(0.1, null, NOW));
    }

    @Test
    void settleDemotesAtMostDailyCap() {
        MemoryMetaMapper mapper = mock(MemoryMetaMapper.class);
        MemoryDecayService service = new MemoryDecayService(mapper);
        List<MemoryMeta> rows = List.of(
                row(1L, 0.10), row(2L, 0.12), row(3L, 0.14));
        when(mapper.selectList(any())).thenReturn(rows);
        when(mapper.updateById(any(MemoryMeta.class))).thenReturn(1);

        // 默认 cap=30，但把字段直接设为 2 验证配额逻辑。
        setField(service, "dailyDemoteCap", 2);
        int updated = service.settle(NOW);

        assertEquals(2, updated);
        ArgumentCaptor<MemoryMeta> captor = ArgumentCaptor.forClass(MemoryMeta.class);
        verify(mapper, org.mockito.Mockito.times(2)).updateById((MemoryMeta) captor.capture());
        assertTrue(captor.getAllValues().stream().allMatch(p -> "dormant".equals(p.getStatus())));
    }

    @Test
    void settleMaterializesDecayWithoutChangingTier() {
        MemoryMetaMapper mapper = mock(MemoryMetaMapper.class);
        MemoryDecayService service = new MemoryDecayService(mapper);
        // 7 天、τ=365：s≈0.922，仍是 active。强度要写回（否则下次从旧基数继续乘，
        // 越滚越慢），但档位不该动。
        when(mapper.selectList(any())).thenReturn(List.of(row(9L, 1.0, NOW.minusDays(7))));
        when(mapper.updateById(any(MemoryMeta.class))).thenReturn(1);
        service.settle(NOW);

        ArgumentCaptor<MemoryMeta> captor = ArgumentCaptor.forClass(MemoryMeta.class);
        verify(mapper).updateById((MemoryMeta) captor.capture());
        MemoryMeta patch = captor.getValue();
        assertEquals(0.922, patch.getStrength().doubleValue(), 0.005);
        assertEquals(null, patch.getFadeLevel());
        assertEquals(NOW, patch.getLastDecayAt());
    }

    private static MemoryMeta row(long id, double strength) {
        return row(id, strength, NOW.minusDays(400));
    }

    private static MemoryMeta row(long id, double strength, LocalDateTime lastDecayAt) {
        MemoryMeta row = new MemoryMeta();
        row.setId(id);
        row.setStrength(java.math.BigDecimal.valueOf(strength));
        row.setTauDays(365);
        row.setFadeLevel("active");
        row.setStatus("active");
        row.setLastDecayAt(lastDecayAt);
        return row;
    }

    private static void setField(Object target, String name, Object value) {
        try {
            java.lang.reflect.Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
