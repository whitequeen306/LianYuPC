package com.lianyu.common.util;

/**
 * 字符串小工具集合（仅收无业务语义的纯函数，业务规则勿放这里）。
 */
public final class TextUtils {

    private TextUtils() {
    }

    /**
     * 去除首尾空白；null 或空白串返回 null。
     */
    public static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * 沿 cause 链收集所有非空 message（上限 8 层，" | " 分隔）。
     * 用于把上游嵌套异常翻译成可读提示时的原始信息采集。
     */
    public static String collectThrowableMessages(Throwable e) {
        StringBuilder sb = new StringBuilder();
        Throwable cur = e;
        int depth = 0;
        while (cur != null && depth < 8) {
            if (cur.getMessage() != null && !cur.getMessage().isBlank()) {
                if (!sb.isEmpty()) {
                    sb.append(" | ");
                }
                sb.append(cur.getMessage());
            }
            cur = cur.getCause();
            depth++;
        }
        return sb.toString();
    }
}
