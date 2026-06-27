package com.lianyu.service.ai;

import java.util.regex.Pattern;

/**
 * 模型原文 → 可落库/可下发的规范格式（心理活动括号完整、括号内无破坏性换行）。
 */
public final class AssistantContentNormalizer {

    private static final Pattern MULTI_SPACE = Pattern.compile("\\s{2,}");

    private AssistantContentNormalizer() {
    }

    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String text = raw.replace("\r\n", "\n").trim();
        text = ParenthesisUtils.stripLeadingOrphanCloses(text);
        text = flattenNewlinesInsideParentheses(text);
        text = closeUnclosedParentheses(text);
        text = MULTI_SPACE.matcher(text).replaceAll(" ").trim();
        return text;
    }

    private static String flattenNewlinesInsideParentheses(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '\n' && ParenthesisUtils.isInsideParentheses(text, i)) {
                if (out.length() > 0 && out.charAt(out.length() - 1) != ' ') {
                    out.append(' ');
                }
                continue;
            }
            out.append(ch);
        }
        return out.toString();
    }

    private static String closeUnclosedParentheses(String text) {
        int depth = ParenthesisUtils.countUnclosedDepth(text);
        if (depth <= 0) {
            return text;
        }
        StringBuilder sb = new StringBuilder(text);
        for (int i = 0; i < depth; i++) {
            sb.append('）');
        }
        return sb.toString();
    }
}
