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
}
