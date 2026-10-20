package com.lianyu.service.agent;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 聊天回合端口：Java → Python LangGraph 的**唯一**入口。
 *
 * <p>这是「编排归 Python」的结构性保证：service 层拿到的是一个端口，
 * 而不是一张可以自己往里塞节点的图。删掉 Java 的 Spring AI Alibaba Graph 后，
 * 想再加一个编排步骤，唯一的地方是 `backend-agent/app/chat_turn.py`。
 *
 * <p>返回语义（与其它 agent 端口一致）：
 * <ul>
 *   <li>{@code null} —— agent 不可用 / 用户没有可用的文本模型凭据：
 *       调用方必须报错（Java 侧已无本地回退）</li>
 *   <li>非 null —— 结果有效</li>
 * </ul>
 */
public interface ChatTurnPort {

    /** 只组装（不调模型）：需要 prompt/messages 的调用方用（图片回合、对比校验）。 */
    ChatTurnOutcome assemble(ChatTurnRequest request);

    /** 组装 + 阻塞调用模型，返回回复与 usage。 */
    ChatTurnOutcome invoke(ChatTurnRequest request);

    /** 组装 + 流式调用模型：返回已接管的 SSE，增量经回调落库。 */
    SseEmitter stream(ChatTurnRequest request, AgentStreamCallback callback);
}
