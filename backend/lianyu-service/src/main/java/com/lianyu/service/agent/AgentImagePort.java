package com.lianyu.service.agent;

import com.lianyu.service.dto.MessageDto;
import java.util.List;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 图片消息流式端口：AGENT 模式下把「识图 + 生成」交给 backend-agent。
 *
 * <p>取图（对象存储 → 字节 → base64）由实现方负责，端口只描述语义，
 * 这样 service 层不依赖 web 层也不依赖存储细节。
 *
 * @return null 表示失败（凭据缺失 / 取图失败 / Python 不可用）；OFF 退役后调用方直接报错
 */
public interface AgentImagePort {

    /**
     * 阻塞图片回合（非流式单聊 / 群聊等）。
     *
     * @return 回复与识图描述；null 表示失败
     */
    AgentChatResult completeIfActive(long userId,
                                     String systemPrompt,
                                     List<MessageDto> messages,
                                     String imageUrl,
                                     String provider,
                                     String model,
                                     int maxTokens);

    SseEmitter streamIfActive(long userId,
                              String systemPrompt,
                              List<MessageDto> messages,
                              String imageUrl,
                              String provider,
                              String model,
                              int maxTokens,
                              AgentStreamCallback callback);
}
