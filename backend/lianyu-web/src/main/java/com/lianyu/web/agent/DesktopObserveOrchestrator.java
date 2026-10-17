package com.lianyu.web.agent;

import com.lianyu.service.ai.ApiKeyVaultService;
import com.lianyu.service.ai.VisionChatService;
import com.lianyu.service.dto.VaultEntryResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 桌面感知链路的 strangler 编排：Java 老链路 + Python 新链路，按 mode 分派。
 *
 * <ul>
 *   <li>OFF    —— 纯 Java（现状，默认）</li>
 *   <li>SHADOW —— Java 出结果返回给用户；Python 同步影子跑一次，仅记录 diff</li>
 *   <li>AGENT  —— Python 出结果；Python 失败/为空时回退 Java（灰度安全网）</li>
 * </ul>
 *
 * <p>行为保真约束：三种模式下用户可见结果在 Python 链路完全稳定前，等价于现状。
 */
@Slf4j
@Service
public class DesktopObserveOrchestrator implements com.lianyu.service.agent.AgentRuntimePort {

    private final VisionChatService visionChatService;
    private final AgentRuntimeClient agentClient;
    private final ApiKeyVaultService vaultService;

    public DesktopObserveOrchestrator(VisionChatService visionChatService,
                                      AgentRuntimeClient agentClient,
                                      ApiKeyVaultService vaultService) {
        this.visionChatService = visionChatService;
        this.agentClient = agentClient;
        this.vaultService = vaultService;
    }

    /**
     * @return 最终问候语；两侧都失败返回 null（与现状语义一致）
     */
    public String observe(long userId,
                          String imageBase64,
                          String windowTitle,
                          String persona,
                          String provider,
                          String model) {
        AgentRuntimeProperties.Mode mode = agentClient.properties().mode();
        if (mode == AgentRuntimeProperties.Mode.AGENT) {
            String fromAgent = callAgent(userId, imageBase64, windowTitle, persona, provider, model);
            if (fromAgent != null && !fromAgent.isBlank()) {
                return fromAgent;
            }
            log.info("Desktop observe: agent mode returned empty, falling back to Java");
            // fall through to Java
        }

        String fromJava = visionChatService.observeDesktop(
                userId, imageBase64, windowTitle, persona, provider, model);

        if (mode == AgentRuntimeProperties.Mode.SHADOW) {
            String fromAgent = callAgent(userId, imageBase64, windowTitle, persona, provider, model);
            recordShadowDiff(userId, fromJava, fromAgent);
        }
        return fromJava;
    }

    private String callAgent(long userId, String imageBase64, String windowTitle,
                             String persona, String provider, String model) {
        // Stage 2 凭据：Java 侧解析用户 vault 后显式传给 Python（仅内网）；
        // 解析失败不影响影子（Python 侧凭据为空时自身也会静默返回 null）
        String textBaseUrl = null;
        String textApiKey = null;
        String textModel = null;
        try {
            VaultEntryResponse vault = vaultService.resolveForChat(userId, provider);
            if (vault != null) {
                textBaseUrl = vault.getBaseUrl();
                textApiKey = vault.getId() != null
                        ? vaultService.decryptKeyForChat(vault.getId())
                        : vault.getApiKey();
                textModel = model != null && !model.isBlank() ? model.trim() : vault.getModelDefault();
            }
        } catch (Exception e) {
            log.debug("Desktop observe: vault resolve for agent failed: {}", e.toString());
        }
        return agentClient.desktopObserve(
                userId, imageBase64, windowTitle, persona, textBaseUrl, textApiKey, textModel);
    }

    private String callProactiveAgent(long userId, String scene, String persona, String taskBlock,
                                      java.util.List<com.lianyu.service.dto.MessageDto> history,
                                      String provider, String model, String expectedLanguage, Long characterId) {
        java.util.List<Map<String, String>> historyMaps = new java.util.ArrayList<>();
        if (history != null) {
            for (com.lianyu.service.dto.MessageDto dto : history) {
                if (dto == null || dto.getRole() == null) {
                    continue;
                }
                historyMaps.add(Map.of("role", dto.getRole(), "content", dto.getContent() == null ? "" : dto.getContent()));
            }
        }
        return callProactiveAgentMaps(userId, scene, persona, taskBlock, historyMaps, provider, model, expectedLanguage, characterId);
    }

    /** 端口路径（已是 Map 形态的历史）。 */
    private String callProactiveAgentMaps(long userId, String scene, String persona, String taskBlock,
                                          List<Map<String, String>> historyMaps,
                                          String provider, String model,
                                          String expectedLanguage, Long characterId) {
        String textBaseUrl = null;
        String textApiKey = null;
        String textModel = null;
        try {
            VaultEntryResponse vault = vaultService.resolveForChat(userId, provider);
            if (vault != null) {
                textBaseUrl = vault.getBaseUrl();
                textApiKey = vault.getId() != null
                        ? vaultService.decryptKeyForChat(vault.getId())
                        : vault.getApiKey();
                textModel = model != null && !model.isBlank() ? model.trim() : vault.getModelDefault();
            }
        } catch (Exception e) {
            log.debug("Proactive turn: vault resolve for agent failed: {}", e.toString());
        }
        // 主动发言链路在 Java 侧会挂的只读工具（对齐 ToolManager.resolveToolCallbacks）。
        // 不含 write_diary / computer_task：Python 侧尚未实现，不应声明。
        List<String> tools = List.of("get_current_time", "get_weather", "memory_search", "get_my_recent_life");
        return agentClient.proactiveTurn(
                userId, scene, persona, taskBlock, historyMaps, textBaseUrl, textApiKey, textModel,
                provider, expectedLanguage, characterId, tools);
    }

    private void recordProactiveDiff(long userId, String scene, String fromJava, String fromAgent) {
        if (fromJava == null && fromAgent == null) {
            log.info("Proactive shadow[{}][{}]: both-null", userId, scene);
            return;
        }
        if (fromJava != null && fromAgent != null && fromJava.equals(fromAgent)) {
            log.info("Proactive shadow[{}][{}]: match ({} chars)", userId, scene, fromJava.length());
            return;
        }
        log.warn("Proactive shadow[{}][{}]: DIFF java={}chars agent={}chars", userId, scene,
                fromJava == null ? "null" : fromJava.length(),
                fromAgent == null ? "null" : fromAgent.length());
    }

    private void recordShadowDiff(long userId, String fromJava, String fromAgent) {
        if (fromJava == null && fromAgent == null) {
            log.info("Shadow observe[{}]: both-null", userId);
            return;
        }
        if (fromJava != null && fromAgent != null && fromJava.equals(fromAgent)) {
            log.info("Shadow observe[{}]: match ({} chars)", userId, fromJava.length());
            return;
        }
        // LLM 生成天然非确定：不等价不算事故，只记 WARN 供人工/脚本统计漂移率
        log.warn("Shadow observe[{}]: DIFF java={}chars agent={}chars", userId,
                fromJava == null ? "null" : fromJava.length(),
                fromAgent == null ? "null" : fromAgent.length());
    }

    // ── AgentRuntimePort（service 层调用的接缝）──

    @Override
    public com.lianyu.service.agent.AgentTurnResult proactiveTurn(long userId, String scene, String persona,
                                String taskBlock, List<Map<String, String>> history,
                                String provider, String model, Long characterId) {
        // 端口语义：仅 AGENT 模式出结果；OFF/SHADOW 返回 null，由调用方走 Java 原链路
        if (agentClient.properties().mode() != AgentRuntimeProperties.Mode.AGENT) {
            return null;
        }
        com.lianyu.service.agent.AgentTurnResult fromAgent = agentClient.proactiveTurnResult(
                userId, scene, persona, taskBlock, history, provider, model, null, characterId);
        if (fromAgent == null || !fromAgent.isUsable()) {
            log.info("Proactive turn[{}][{}]: agent empty, fallback to Java", userId, scene);
            return null;
        }
        return fromAgent;
    }

    @Override
    public boolean agentActive() {
        return agentClient.properties().mode() == AgentRuntimeProperties.Mode.AGENT;
    }

    @Override
    public String mode() {
        return agentClient.properties().mode().name();
    }
}
