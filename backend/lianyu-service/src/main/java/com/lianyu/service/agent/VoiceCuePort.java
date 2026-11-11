package com.lianyu.service.agent;

import java.util.Map;

/**
 * 语音通话的脚本化台词（开场、冷场）。
 *
 * <p>文案与模型调用都在 Python：Java 只传事实（角色/会话/场景 kind），
 * 实现方负责凭据解析与降级。失败返回 {@code null}，通话静默继续 ——
 * 开场生成不出来不能影响通话本身。
 */
public interface VoiceCuePort {

    /**
     * @param kind {@code opening}（P1）；{@code idle} 留给 P2 冷场主动
     * @return 可直接朗读的一句台词；失败/不可用返回 null
     */
    String generateCue(long userId,
                       Long characterId,
                       Long conversationId,
                       String characterName,
                       Map<String, Object> settings,
                       String kind);
}
