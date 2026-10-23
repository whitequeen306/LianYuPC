package com.lianyu.web.agent;

import com.lianyu.service.ai.ApiKeyVaultService;
import com.lianyu.service.dto.VaultEntryResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 桌面感知链路的 agent 编排（OFF 退役后不再有 Java 识图回退）。
 */
@Slf4j
@Service
public class DesktopObserveOrchestrator implements com.lianyu.service.agent.AgentRuntimePort {

    private final AgentRuntimeClient agentClient;
    private final ApiKeyVaultService vaultService;

    public DesktopObserveOrchestrator(AgentRuntimeClient agentClient,
                                      ApiKeyVaultService vaultService) {
        this.agentClient = agentClient;
        this.vaultService = vaultService;
    }

    /**
     * @return 问候语；agent 失败/为空返回 null
     */
    public String observe(long userId,
                          String imageBase64,
                          String windowTitle,
                          String persona,
                          String provider,
                          String model) {
        String fromAgent = callAgent(userId, imageBase64, windowTitle, persona, provider, model);
        if (fromAgent == null || fromAgent.isBlank()) {
            log.info("Desktop observe: agent returned empty, userId={}", userId);
            return null;
        }
        return fromAgent;
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
        // 主动发言链路挂的只读工具（能力声明归 Python，Java 只声明要哪些）。
        // 不含 write_diary / 桌面 desk_*：后者是否下发由 Python 按桌面在线状态决定。
        List<String> tools = List.of("get_current_time", "get_weather", "memory_search", "get_my_recent_life");
        return agentClient.proactiveTurn(
                userId, scene, persona, taskBlock, historyMaps, textBaseUrl, textApiKey, textModel,
                provider, expectedLanguage, characterId, tools);
    }

    // ── AgentRuntimePort（service 层调用的接缝）──

    @Override
    public com.lianyu.service.agent.AgentTurnResult proactiveTurn(long userId, String scene, String persona,
                                String taskBlock, List<Map<String, String>> history,
                                String provider, String model, Long characterId) {
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
        com.lianyu.service.agent.AgentTurnResult fromAgent = agentClient.proactiveTurnResult(
                userId, scene, persona, taskBlock, history, textBaseUrl, textApiKey, textModel,
                provider, model, null, characterId);
        if (fromAgent == null || !fromAgent.isUsable()) {
            log.info("Proactive turn[{}][{}]: agent empty, fallback to Java", userId, scene);
            return null;
        }
        return fromAgent;
    }

    @Override
    public boolean agentActive() {
        return true;
    }

    @Override
    public String mode() {
        return "AGENT";
    }
}
