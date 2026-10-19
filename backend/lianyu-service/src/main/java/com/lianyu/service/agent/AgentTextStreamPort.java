package com.lianyu.service.agent;

import com.lianyu.service.dto.MessageDto;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * 语音通话用的 token 流端口：把每个增量交给 TTS，同时返回完整文本。
 *
 * <p>与 {@link AgentStreamPort} 的区别：不需要 SSE/落库回调，只需要 token 增量。
 */
public interface AgentTextStreamPort {

    /**
     * @param onDelta 每个增量回调（可为 null）
     * @return 完整文本；失败时 future 异常完成
     */
    CompletableFuture<String> stream(long userId,
                                     String systemPrompt,
                                     List<MessageDto> messages,
                                     String provider,
                                     String model,
                                     String expectedLanguage,
                                     Consumer<String> onDelta);
}
