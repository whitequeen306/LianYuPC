package com.lianyu.service.ai;

import java.util.regex.Pattern;

/**
 * 模型原文 → 可落库/可下发的规范格式（心理活动括号完整、单条气泡内无硬换行）。
 */
public final class AssistantContentNormalizer {

    private static final Pattern MULTI_SPACE = Pattern.compile("\\s{2,}");
    /** 阻止闭括号被软换行甩到下一行行首 */
    private static final char WORD_JOINER = '\u2060';

    private AssistantContentNormalizer() {
    }

    /**
     * 切气泡前：去掉括号内破坏性换行并补全括号，但保留括号外换行供多气泡拆分。
     */
    public static String prepareForSplit(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String text = raw.replace("\r\n", "\n").replace("\r", "\n").trim();
        text = text.replace(String.valueOf(WORD_JOINER), "");
        text = ParenthesisUtils.stripLeadingOrphanCloses(text);
        text = flattenNewlinesInsideParentheses(text);
        return closeUnclosedParentheses(text);
    }

    /**
     * 单条气泡正文：压平硬换行、补全括号、粘住闭括号，避免「一句一行」和孤立 `）`。
     */
    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String text = prepareForSplit(raw);
        if (text.isBlank()) {
            return "";
        }
        text = text.replace('\n', ' ');
        text = MULTI_SPACE.matcher(text).replaceAll(" ").trim();
        return glueClosingParentheses(text);
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

    private static String glueClosingParentheses(String text) {
        if (text.isEmpty()) {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length() + 8);
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '）' || ch == ')') {
                while (!out.isEmpty() && out.charAt(out.length() - 1) == ' ') {
                    out.setLength(out.length() - 1);
                }
                if (out.isEmpty() || out.charAt(out.length() - 1) != WORD_JOINER) {
                    out.append(WORD_JOINER);
                }
            }
            out.append(ch);
        }
        return out.toString();
    }
}
