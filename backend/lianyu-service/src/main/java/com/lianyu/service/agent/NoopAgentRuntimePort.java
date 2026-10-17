package com.lianyu.service.agent;

import java.util.List;
import java.util.Map;

/** 空实现：OFF 模式语义（一切调用返回 null/关态）。缺省兜底，避免调用方判空。 */
public class NoopAgentRuntimePort implements AgentRuntimePort {

    @Override
    public AgentTurnResult proactiveTurn(long userId, String scene, String persona, String taskBlock,
                                List<Map<String, String>> history, String provider, String model,
                                Long characterId) {
        return null;
    }

    @Override
    public boolean agentActive() {
        return false;
    }
}
