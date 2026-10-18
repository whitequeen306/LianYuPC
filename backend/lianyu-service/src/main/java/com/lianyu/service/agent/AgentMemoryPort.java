package com.lianyu.service.agent;

import java.util.List;

/**
 * 记忆写入端口：把「抽取 + 落库」整体交给 backend-agent。
 *
 * <p>为什么是整体而不是拆分：抽取是 LLM 任务、写入是同事务的两张表，
 * 拆到两个语言里会让「读 Java 写的表再写一遍」的中间态长期存在。
 *
 * @return true 表示 Python 已接管（含"没有可写内容"）；false 表示未接管，调用方回退 Java
 */
public interface AgentMemoryPort {

    boolean extractAndWriteIfActive(long userId,
                                    Long characterId,
                                    List<AgentMemoryMessage> messages,
                                    String provider,
                                    String model);

    /** 端口用的消息视图（避免 service 层依赖 dao 实体）。 */
    record AgentMemoryMessage(Long id, String role, String content) {
    }
}
