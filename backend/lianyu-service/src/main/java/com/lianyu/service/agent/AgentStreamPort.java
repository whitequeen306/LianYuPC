package com.lianyu.service.agent;

import com.lianyu.service.dto.MessageDto;
import java.util.List;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 单聊流式回合端口：AGENT 模式下把流式生成委托给 backend-agent。
 *
 * <p>与 {@link AgentRuntimePort} 同样是 service→web 的依赖倒置接缝。
 *
 * <p>返回语义：
 * <ul>
 *   <li>{@code null} —— 当前不是 AGENT 模式，或缺少用户文本凭据：
 *       调用方必须报错（无 Java 回退）</li>
 *   <li>{@code SseEmitter} —— 已接管本次流式，调用方直接把它返回给客户端</li>
 * </ul>
 */
public interface AgentStreamPort {

    SseEmitter streamIfActive(long userId,
                              String systemPrompt,
                              List<MessageDto> messages,
                              String provider,
                              String model,
                              String expectedLanguage,
                              Long characterId,
                              int maxTokens,
                              AgentStreamCallback callback);
}
