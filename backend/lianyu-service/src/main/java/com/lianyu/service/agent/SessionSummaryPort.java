package com.lianyu.service.agent;

/**
 * 会话摘要生成端口（合并 / 压缩）。
 *
 * <p>摘要的**文案与模型调用**都归 Python；Java 只负责「什么时候需要摘要」
 * 这类产品规则，以及回退。
 *
 * <p>返回 {@code null} 表示未接管，调用方回退 Java 原实现。
 */
public interface SessionSummaryPort {

    /**
     * @param mode            merge / compress
     * @param existingSummary 已有摘要（合并的基底；压缩的输入）
     * @param messages        待合并的新增消息（compress 时为空）
     * @param targetChars     压缩目标字数
     * @return 新的摘要正文；null 表示回退
     */
    String generate(long userId, String provider, String model, String mode,
                    String existingSummary, java.util.List<SummaryMessage> messages,
                    int targetChars);

    /** 与 Python 端 role/content 对齐的最小消息结构。 */
    record SummaryMessage(String role, String content) {
    }
}
