package com.lianyu.web.agent;

import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 每周把上一周的 episode 收成一条周记（Python `/agent/memory/consolidate`）。
 *
 * <p>为什么放周一凌晨：周记按自然周对齐，周一跑才不会把上周日切到两半；
 * 也避开 03:00 的关系/情绪结算与 03:30 的记忆衰减。
 *
 * <p>默认关闭（{@code lianyu.memory.consolidate.enabled=false}）：它会主动发一次
 * 内网 HTTP，本地起服务时不该默默打网络。线上显式打开。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "lianyu.memory.consolidate.enabled", havingValue = "true")
public class MemoryConsolidateScheduler {

    private final AgentRuntimeClient client;

    @Scheduled(cron = "${lianyu.memory.consolidate.cron:0 10 4 * * MON}")
    public void weeklyDigest() {
        if (!client.properties().agentActive()) {
            return;
        }
        try {
            String response = client.postJson("/agent/memory/consolidate", 0L, Map.of());
            log.info("memory consolidate: {}", response);
        } catch (Exception e) {
            // 周记失败只影响"那段日子"能不能被一句话想起，不能影响任何在线请求。
            log.warn("memory consolidate failed: {}", e.toString());
        }
    }
}
