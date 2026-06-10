package com.lianyu.service.ai;

import java.util.regex.Pattern;

/**
 * 剥离括号内心独白（全角/半角）。不处理【】、*动作*。
 * 用户关闭「心理活动」时作展示与落库前的兜底过滤。
 */
public final class InnerThoughtFilter {

    private static final Pattern FULL_WIDTH_PARENS = Pattern.compile("（[^（）]*）");
    private static final Pattern HALF_WIDTH_PARENS = Pattern.compile("\\([^()]*\\)");

    private InnerThoughtFilter() {
    }

    public static String stripIfDisabled(String text, boolean showInnerThoughts) {
        if (showInnerThoughts || text == null || text.isBlank()) {
            return text == null ? "" : text;
        }
        return strip(text);
    }

    public static String strip(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String result = FULL_WIDTH_PARENS.matcher(text).replaceAll("");
        result = HALF_WIDTH_PARENS.matcher(result).replaceAll("");
        return result.replaceAll("\\s{2,}", " ").trim();
    }

    public static boolean isEmptyAfterStrip(String text) {
        return strip(text).isBlank();
    }
}
