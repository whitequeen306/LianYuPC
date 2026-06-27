package com.lianyu.service.ai;

import java.util.ArrayList;
import java.util.List;

/**
 * 括号深度与气泡切分：防止心理活动（括号内容）被拆到多条消息。
 */
public final class ParenthesisUtils {

    private ParenthesisUtils() {
    }

    private static boolean isOpenParen(char ch) {
        return ch == '（' || ch == '(';
    }

    private static boolean isCloseParen(char ch) {
        return ch == '）' || ch == ')';
    }

    public static int countUnclosedDepth(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        int depth = 0;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (isOpenParen(ch)) {
                depth++;
            } else if (isCloseParen(ch) && depth > 0) {
                depth--;
            }
        }
        return depth;
    }

    public static String stripLeadingOrphanCloses(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        int i = 0;
        while (i < text.length()) {
            char ch = text.charAt(i);
            if (isCloseParen(ch)) {
                i++;
                continue;
            }
            if (ch == ' ' || ch == '\n' || ch == '\r' || ch == '\t') {
                i++;
                continue;
            }
            break;
        }
        return text.substring(i);
    }

    public static boolean isInsideParentheses(String text, int index) {
        if (text == null || index <= 0) {
            return false;
        }
        int depth = 0;
        for (int i = 0; i < index; i++) {
            char ch = text.charAt(i);
            if (isOpenParen(ch)) {
                depth++;
            } else if (isCloseParen(ch) && depth > 0) {
                depth--;
            }
        }
        return depth > 0;
    }

    public static List<String> splitLinesOutsideParentheses(String text) {
        String normalized = text.replace("\r\n", "\n");
        List<String> lines = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < normalized.length(); i++) {
            char ch = normalized.charAt(i);
            if (ch == '\n' && !isInsideParentheses(normalized, i)) {
                String trimmed = current.toString().trim();
                if (!trimmed.isBlank()) {
                    lines.add(trimmed);
                }
                current.setLength(0);
                continue;
            }
            current.append(ch);
        }
        String trimmed = current.toString().trim();
        if (!trimmed.isBlank()) {
            lines.add(trimmed);
        }
        return lines.isEmpty() ? List.of(normalized.trim()) : lines;
    }

    public static List<String> rebalanceSplitPieces(List<String> pieces) {
        if (pieces == null || pieces.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        String pending = "";
        for (String raw : pieces) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String piece = raw.trim();
            String combined = pending.isEmpty() ? piece : pending + "\n" + piece;
            if (countUnclosedDepth(combined) > 0) {
                pending = combined;
                continue;
            }
            String cleaned = stripLeadingOrphanCloses(combined).trim();
            if (!cleaned.isBlank()) {
                out.add(cleaned);
            }
            pending = "";
        }
        String tail = stripLeadingOrphanCloses(pending).trim();
        if (!tail.isBlank()) {
            out.add(tail);
        }
        return out;
    }
}
