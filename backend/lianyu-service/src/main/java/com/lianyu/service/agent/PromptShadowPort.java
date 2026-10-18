package com.lianyu.service.agent;

/**
 * prompt 级影子比对端口（service → web 的依赖倒置）。
 *
 * <p>由 {@code ChatTurnFacade} 在 Java 组装完 prompt 后调用；实现方负责
 * **异步**发起 Python 组装并比对，绝不允许阻塞或抛异常影响用户请求。
 */
public interface PromptShadowPort {

    /**
     * 异步比对一次；实现方必须吞掉所有异常并自行记录。
     *
     * @param javaPrompt Java 实际使用的 system prompt（比对基准）
     */
    void compareAsync(long userId, AssemblySnapshot snapshot, String javaPrompt);
}
