package com.lianyu.service.memory;

import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 每夜 03:30 结算记忆衰减（架构文档 §9）。
 *
 * <p>为什么是凌晨：这是唯一不影响回复延迟的窗口；为什么默认开启但可关：
 * 衰减改的是"她还能不能想起来"，误伤比不衰减更难解释，出问题先关它。
 *
 * <p>03:00 是关系/情绪的结算，03:30 是记忆——错开半小时，避免两个批处理
 * 同时抢连接池。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MemoryDecayScheduler {

    private final MemoryDecayService memoryDecayService;

    @Value("${lianyu.memory.decay.enabled:true}")
    private boolean enabled;

    @Scheduled(cron = "${lianyu.memory.decay.cron:0 30 3 * * *}")
    public void settleMemory() {
        if (!enabled) {
            return;
        }
        try {
            int updated = memoryDecayService.settle(LocalDateTime.now());
            if (updated > 0) {
                log.info("Memory decay settled {} rows", updated);
            }
        } catch (Exception e) {
            log.warn("Memory decay tick failed: {}", e.toString());
        }
    }
}
