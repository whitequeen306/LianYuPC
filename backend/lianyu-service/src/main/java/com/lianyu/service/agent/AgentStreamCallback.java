package com.lianyu.service.agent;

import java.io.IOException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 流式回合的收尾回调（原 `AiChatService.StreamCallback`）。
 *
 * <p>Java 不再持有任何模型调用后，这个类型必须从 AI 服务里搬出来：
 * 它描述的是「一条流怎么收尾」，与用哪家模型无关。
 */
public interface AgentStreamCallback {

    void onComplete(String fullContent, Throwable error);

    /** 图片消息识图完成后回调（在 SSE 正文下发前），用于落库历史占位文案。 */
    default void onVisionComplete(String imageDescription) {
    }

    /** 在发送 [DONE] 之前调用，可向客户端推送规范化后的分片（pieces）等。 */
    default void beforeStreamComplete(SseEmitter emitter, String fullContent) throws IOException {
    }
}
