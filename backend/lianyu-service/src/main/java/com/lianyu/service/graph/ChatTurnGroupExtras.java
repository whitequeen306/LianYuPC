package com.lianyu.service.graph;

/**
 * 群聊场景的附加组装参数（原 `ChatTurnPromptAssembler.GroupExtras`）。
 *
 * <p>Java 不再组装 prompt，只把这三项事实传给 agent。
 */
public record ChatTurnGroupExtras(int maxPieces, String otherCharactersLine, String mentionCtx) {
}
