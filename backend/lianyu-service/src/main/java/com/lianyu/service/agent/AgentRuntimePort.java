package com.lianyu.service.agent;

import java.util.List;
import java.util.Map;

/**
 * Agent 运行时端口（service 层定义、web 层实现）——strangler 迁移的接缝。
 *
 * <p>依赖方向：service → 端口接口；web.agent 编排器实现该接口并注入。
 * 这样 ProactiveMessageService / MomentsService / CharacterDiaryService
 * 可以在不动依赖方向的前提下把生成段委托给 backend-agent（Python/LangGraph）。
 *
 * <p>所有实现必须满足：任何失败返回 null（静默降级），绝不抛出影响主链路。
 */
public interface AgentRuntimePort {

    /**
     * 主动发言回合（定时主动/冷启动/跟进/城市变更共用一张 LangGraph 图）。
     *
     * @param scene     场景标识（PROACTIVE / COLD_OPEN / COLD_OPEN_FOLLOWUP / CITY_CHANGE）
     * @param persona   Java 组装好的 system 主体（人设+记忆+关系+情绪+时间锚点等）
     * @param taskBlock 场景任务块（资源化 prompt 渲染结果，可空）
     * @param history   近窗历史（不含 system；role 小写 user/assistant）
     * @return assistantRaw，或 null（无凭据/失败/关闭）
     */
    AgentTurnResult proactiveTurn(long userId,
                         String scene,
                         String persona,
                         String taskBlock,
                         List<Map<String, String>> history,
                         String provider,
                         String model,
                         Long characterId);

    /** OFF 退役后始终为 true；保留方法便于调用点/日志过渡。 */
    boolean agentActive();

    /** 运行路径名，用于日志（固定 AGENT）。 */
    default String mode() {
        return "AGENT";
    }
}
