package com.lianyu.service.agent;

/**
 * prompt 组装端口：AGENT 模式下由 Python 接管组装。
 *
 * <p>返回 {@code null} 表示「本次不使用 agent 组装」（非 AGENT 模式、agent 不可用、
 * 组装失败）——调用方必须回退到 Java 组装，因此这里**不允许抛异常**。
 *
 * <p>为什么要端口化：`AssemblePromptNode` 在 service 层，而 HTTP 实现在 web 层，
 * 依赖方向不能反过来。
 */
public interface PromptAssemblyPort {

    /**
     * 尝试用 agent 组装 system prompt。
     *
     * @return 组装结果；{@code null} 表示不可用，调用方走 Java 组装
     */
    String assembleIfEnabled(PromptAssemblyRequest request);
}
