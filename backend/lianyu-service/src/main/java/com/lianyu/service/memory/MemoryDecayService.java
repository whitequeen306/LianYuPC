package com.lianyu.service.memory;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lianyu.dao.entity.MemoryMeta;
import com.lianyu.dao.mapper.MemoryMetaMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 记忆的可及性衰减（架构文档 §7.6）。
 *
 * <p>只改 {@code strength} / {@code last_decay_at} / {@code fade_level}，**从不删行**：
 * 忘记是"想不起来"，不是"没发生过"。dormant 的向量由 relay 侧清，
 * 行本身还留着，用户提起时能复活。
 *
 * <p>为什么不在 Python 里算：读路径每轮都要算 s_eff（惰性求值，零写），
 * 但"把漂移物化进库"是批处理，放 Java 与关系衰减同构；两边用的是同一公式：
 * 见 {@code backend-agent/app/memory/retrieve.py::effective_strength}，
 * 改动时必须两边一起改，并有单测钉住数值。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryDecayService {

    /** 四档阈值：低于它就降档。 */
    public static final double FADED_BELOW = 0.60;
    public static final double ARCHIVED_BELOW = 0.30;
    public static final double DORMANT_BELOW = 0.15;
    /** dormant 的"多久没被想起"门槛。 */
    public static final int DORMANT_DAYS = 90;
    /** 只结算超过这个天数没动过的行，避免每天全表扫。 */
    public static final int SETTLE_AFTER_DAYS = 7;

    private static final List<String> TIERS = List.of("active", "faded", "archived", "dormant");

    private final MemoryMetaMapper memoryMetaMapper;

    @Value("${lianyu.memory.decay.batch-limit:2000}")
    private int batchLimit = 2000;

    @Value("${lianyu.memory.decay.daily-demote-cap:30}")
    private int dailyDemoteCap = 30;

    /**
     * 双时间尺度：近期掉得快、远期拖得长。
     *
     * <p>单指数在 1 天处就掉到 30%，会把"昨天刚聊过"的事也变模糊；
     * 两条指数叠加后 1 天 ≈ 0.83、30 天 ≈ 0.55、1 年 ≈ 0.18（τ=365）。
     */
    public static final int MAX_DECAY_DAYS = 30;

    /**
     * 结算用的强度：一次最多按 30 天算。用户半年没上线，按 400 天结算会把
     * 0.66 的记忆一次打成 dormant（实测 0.128）；封顶后仍继续衰减，只是不会
     * 因为"离开很久"被一次性清空。
     */
    public static double settleStrength(double strength, int tauDays,
                                        LocalDateTime lastDecayAt, LocalDateTime now) {
        if (tauDays <= 0 || lastDecayAt == null || now == null) {
            return strength;
        }
        double days = Math.max(0d, Duration.between(lastDecayAt, now).toSeconds() / 86400d);
        return curve(strength, tauDays, Math.min(days, MAX_DECAY_DAYS));
    }

    public static double effectiveStrength(double strength, int tauDays,
                                           LocalDateTime lastDecayAt, LocalDateTime now) {
        if (tauDays <= 0 || lastDecayAt == null || now == null) {
            return 1.0d;
        }
        double days = Math.max(0d, Duration.between(lastDecayAt, now).toSeconds() / 86400d);
        return curve(strength, tauDays, days);
    }

    static double curve(double strength, int tauDays, double days) {
        double fast = Math.exp(-days / (tauDays / 6.0d));
        double slow = Math.exp(-days / (2.5d * tauDays));
        double value = strength * (0.7d * fast + 0.3d * slow);
        return Math.max(0d, Math.min(1d, value));
    }

    public static String tierFor(double strength, LocalDateTime lastRecalledAt, LocalDateTime now) {
        boolean longUnrecalled = lastRecalledAt == null
                || lastRecalledAt.isBefore(now.minusDays(DORMANT_DAYS));
        if (strength < DORMANT_BELOW && longUnrecalled) {
            return "dormant";
        }
        if (strength < ARCHIVED_BELOW) {
            return "archived";
        }
        if (strength < FADED_BELOW) {
            return "faded";
        }
        return "active";
    }

    private static int rank(String tier) {
        int index = TIERS.indexOf(tier == null ? "active" : tier);
        return index < 0 ? 0 : index;
    }

    /**
     * 结算一批；返回实际更新的行数。
     *
     * <p>按 last_decay_at 升序取批，并显式断言"两批覆盖不同 id"——
     * 关系衰减曾因为按 last_interaction_at 排序，936 行里只有 502 行轮到过。
     */
    @Transactional
    public int settle(LocalDateTime now) {
        int limit = Math.max(1, batchLimit);
        List<MemoryMeta> rows = memoryMetaMapper.selectList(
                new LambdaQueryWrapper<MemoryMeta>()
                        .eq(MemoryMeta::getStatus, "active")
                        .gt(MemoryMeta::getTauDays, 0)
                        .isNotNull(MemoryMeta::getLastDecayAt)
                        .lt(MemoryMeta::getLastDecayAt, now.minusDays(SETTLE_AFTER_DAYS))
                        .orderByAsc(MemoryMeta::getLastDecayAt)
                        .last("LIMIT " + limit));
        if (rows == null || rows.isEmpty()) {
            return 0;
        }

        record Candidate(MemoryMeta row, double strength, String tier) {
        }
        List<Candidate> candidates = new ArrayList<>(rows.size());
        for (MemoryMeta row : rows) {
            int tau = row.getTauDays() == null ? 0 : row.getTauDays();
            double base = row.getStrength() == null ? 1.0d : row.getStrength().doubleValue();
            double value = settleStrength(base, tau, row.getLastDecayAt(), now);
            candidates.add(new Candidate(row, value, tierFor(value, row.getLastRecalledAt(), now)));
        }
        candidates.sort(Comparator.comparingDouble(Candidate::strength));

        int demotions = 0;
        int updated = 0;
        for (Candidate candidate : candidates) {
            MemoryMeta row = candidate.row();
            String next = candidate.tier();
            boolean demote = rank(next) > rank(row.getFadeLevel());
            if (demote) {
                if (demotions >= Math.max(0, dailyDemoteCap)) {
                    // 今天的降档配额用完了：这一行原样留着，明晚按 last_decay_at 还会轮到。
                    continue;
                }
                demotions++;
            }
            MemoryMeta patch = new MemoryMeta();
            patch.setId(row.getId());
            patch.setStrength(BigDecimal.valueOf(candidate.strength()).setScale(3, RoundingMode.HALF_UP));
            patch.setLastDecayAt(now);
            if (!next.equals(row.getFadeLevel())) {
                patch.setFadeLevel(next);
            }
            if (demote && ("archived".equals(next) || "dormant".equals(next)) && row.getArchivedAt() == null) {
                patch.setArchivedAt(now);
            }
            if ("dormant".equals(next) && !"dormant".equals(row.getStatus())) {
                patch.setStatus("dormant");
            }
            updated += memoryMetaMapper.updateById((MemoryMeta) patch);
        }
        if (updated > 0) {
            log.debug("Memory decay: {} rows settled, {} demoted", updated, demotions);
        }
        return updated;
    }
}
