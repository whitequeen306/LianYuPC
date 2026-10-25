package com.lianyu.web.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lianyu.ai.SsrfPinningClientFactory;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 模型工具清单的**单一来源**：以 Python {@code /agent/capabilities} 的 {@code supportedTools} 为准。
 *
 * <p><b>为什么需要它</b>：这份清单原先在 Java 里手抄了四份 ——
 * {@code AgentRuntimeClient.PROACTIVE_TOOLS}、{@code AgentStreamAdapter.CHAT_TOOLS}、
 * {@code AgentTextStreamAdapter} 里的内联 {@code List.of(...)}、
 * {@code DesktopObserveOrchestrator} 里的内联 {@code List.of(...)}。
 * 加一个工具必须同时改四处，漏一处模型就永远看不到那个工具（2026-10 的 {@code write_diary}
 * 就是这么"有实现却不可达"的）。现在只有 Python 一处事实来源。
 *
 * <p><b>取不到时怎么办</b>：按「上次成功的快照 → 内置 BOOTSTRAP」降级。
 * 绝不因为 agent 抖动就让模型一个工具都没有 —— 那样时间/天气/记忆全都会开始瞎编。
 *
 * <p>缓存 5 分钟：工具清单是低频变更的部署级事实，不值得每回合打一次网络。
 */
@Slf4j
@Component
public class AgentToolCatalog {

    /**
     * 内置兜底：Python 起步就有的四个只读工具。
     *
     * <p>只在「从未成功取到过快照」时使用；一旦取到过，就用快照（哪怕已过期）。
     */
    private static final List<String> BOOTSTRAP = List.of(
            "get_current_time", "get_weather", "memory_search", "get_my_recent_life");

    /** 快照有效期。 */
    private static final Duration TTL = Duration.ofMinutes(5);

    /** 单次拉取的硬超时（连接 + 读取）；慢过这个数就当取不到，不要拖住对话。 */
    private static final Duration FETCH_TIMEOUT = Duration.ofSeconds(3);

    private final ObjectMapper objectMapper;
    private final RestClient restClient;
    private final Object lock = new Object();
    private final AtomicBoolean warned = new AtomicBoolean(false);

    /** 上一次成功取到的清单；null 表示从未成功。 */
    private volatile List<String> snapshot;
    private volatile long fetchedAtMs;

    @Autowired
    public AgentToolCatalog(AgentRuntimeProperties properties, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        // 与 AgentRuntimeClient 同一套出站纪律：走 SSRF 固定工厂，不裸拼 URL
        this.restClient = SsrfPinningClientFactory.defaultRestClientBuilder()
                .baseUrl(properties.baseUrl())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    /**
     * 本轮该挂给模型的工具名。
     *
     * <p>目前 Python 支持的全是通用只读工具，各链路（单聊 / 主动发言 / 语音）挂的都一样，
     * 所以直接返回全量。将来若某条链路不该拥有某个工具，在这里按链路过滤，
     * **不要**再回到调用方正手抄清单的老路。
     */
    public List<String> chatTools() {
        return supported();
    }

    /** Python 声明的全量工具（带缓存与降级）。 */
    public List<String> supported() {
        List<String> cached = snapshot;
        long now = System.currentTimeMillis();
        if (cached != null && now - fetchedAtMs < TTL.toMillis()) {
            return cached;
        }
        synchronized (lock) {
            cached = snapshot;
            now = System.currentTimeMillis();
            if (cached != null && now - fetchedAtMs < TTL.toMillis()) {
                return cached;
            }
            List<String> fresh = fetch();
            if (fresh != null) {
                snapshot = fresh;
                fetchedAtMs = System.currentTimeMillis();
                return fresh;
            }
            // 取不到：有旧快照就用旧的（工具清单不会频繁变），否则用内置兜底
            return cached != null ? cached : BOOTSTRAP;
        }
    }

    /** 立即失效缓存（测试与「刚部署完想立刻生效」时用）。 */
    public void invalidate() {
        synchronized (lock) {
            fetchedAtMs = 0L;
        }
    }

    private List<String> fetch() {
        try {
            String body = restClient.get()
                    .uri("/agent/capabilities")
                    .retrieve()
                    .body(String.class);
            if (body == null || body.isBlank()) {
                return null;
            }
            JsonNode root = objectMapper.readTree(body);
            JsonNode tools = root.path("supportedTools");
            if (!tools.isArray()) {
                return null;
            }
            List<String> names = new ArrayList<>();
            for (JsonNode node : tools) {
                if (node.isTextual() && !node.asText().isBlank()) {
                    names.add(node.asText().trim());
                }
            }
            return names.isEmpty() ? null : List.copyOf(names);
        } catch (Exception e) {
            // 只警告一次：agent 抖动会持续刷日志，没必要每个进程喊几十遍
            if (warned.compareAndSet(false, true)) {
                log.warn("tool catalog fetch failed, falling back to bootstrap/{}: {}",
                        snapshot == null ? "bootstrap" : "last snapshot", e.toString());
            }
            return null;
        }
    }
}
