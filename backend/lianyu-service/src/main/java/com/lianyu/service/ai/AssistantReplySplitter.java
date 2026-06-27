package com.lianyu.service.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 将 AI 一次输出按换行拆成多条聊天气泡（单聊 / 群聊共用）。
 * 括号内换行不切分；切分后合并括号未闭合片段，避免心理活动错位。
 */
@Component
public class AssistantReplySplitter {

    private static final int SENTENCE_SPLIT_MIN_CHARS = 40;
    private static final Pattern CJK_SENTENCE_BOUNDARY =
            Pattern.compile("(?<=[。！？!?])(?=[^。！？!?\\s])");
    private static final Pattern EN_SENTENCE_BOUNDARY =
            Pattern.compile("(?<=[.!?])\\s+");

    public List<String> split(String fullContent, int maxRepliesPerTurn) {
        if (fullContent == null || fullContent.isBlank()) {
            return List.of();
        }
        String normalized = ParenthesisUtils.stripLeadingOrphanCloses(
                fullContent.replace("\r\n", "\n").trim());

        List<String> pieces = new ArrayList<>(ParenthesisUtils.splitLinesOutsideParentheses(normalized));
        if (pieces.isEmpty()) {
            pieces.add(normalized);
        }

        int limit = Math.max(1, maxRepliesPerTurn);
        if (pieces.size() == 1 && pieces.get(0).length() >= SENTENCE_SPLIT_MIN_CHARS) {
            List<String> sentencePieces = splitBySentenceBoundary(pieces.get(0));
            if (sentencePieces.size() > 1) {
                pieces = sentencePieces;
            }
        }

        pieces = ParenthesisUtils.rebalanceSplitPieces(pieces);

        if (pieces.size() > limit) {
            List<String> merged = new ArrayList<>(pieces.subList(0, limit - 1));
            String tail = String.join(" ", pieces.subList(limit - 1, pieces.size()));
            merged.add(tail.trim());
            return merged;
        }
        return pieces;
    }

    private List<String> splitBySentenceBoundary(String text) {
        List<String> aware = splitBySentenceBoundaryOutsideParentheses(text);
        if (aware.size() > 1) {
            return aware;
        }
        List<String> cjk = splitWithPattern(text, CJK_SENTENCE_BOUNDARY);
        if (cjk.size() > 1) {
            return cjk;
        }
        List<String> en = splitWithPattern(text, EN_SENTENCE_BOUNDARY);
        if (en.size() > 1) {
            return en;
        }
        return List.of(text.trim());
    }

    private static List<String> splitBySentenceBoundaryOutsideParentheses(String text) {
        List<String> pieces = new ArrayList<>();
        int start = 0;
        int i = 0;
        while (i < text.length()) {
            char ch = text.charAt(i);
            int splitAt = -1;
            if (isCjkSentenceEnd(ch) && i + 1 < text.length() && !Character.isWhitespace(text.charAt(i + 1))) {
                if (!ParenthesisUtils.isInsideParentheses(text, i + 1)) {
                    splitAt = i + 1;
                }
            } else if (isEnSentenceEnd(ch)) {
                int j = i + 1;
                while (j < text.length() && Character.isWhitespace(text.charAt(j))) {
                    j++;
                }
                if (j > i + 1 && !ParenthesisUtils.isInsideParentheses(text, i + 1)) {
                    splitAt = j;
                }
            }
            if (splitAt > start) {
                String part = text.substring(start, splitAt).trim();
                if (!part.isBlank()) {
                    pieces.add(part);
                }
                start = splitAt;
                i = splitAt;
                continue;
            }
            i++;
        }
        String tail = text.substring(start).trim();
        if (!tail.isBlank()) {
            pieces.add(tail);
        }
        return pieces.isEmpty() ? List.of(text.trim()) : pieces;
    }

    private static boolean isCjkSentenceEnd(char ch) {
        return ch == '。' || ch == '！' || ch == '？' || ch == '!' || ch == '?';
    }

    private static boolean isEnSentenceEnd(char ch) {
        return ch == '.' || ch == '!' || ch == '?';
    }

    private static List<String> splitWithPattern(String text, Pattern pattern) {
        String[] parts = pattern.split(text);
        List<String> result = new ArrayList<>();
        for (String part : parts) {
            String trimmed = part == null ? "" : part.trim();
            if (!trimmed.isBlank()) {
                result.add(trimmed);
            }
        }
        return result.isEmpty() ? List.of(text.trim()) : result;
    }

    /** @deprecated 按行切分后不再需要合并段内软换行；保留供测试或外部引用 */
    public String collapseSoftLineBreaks(String text) {
        if (text == null || text.isBlank()) {
            return text == null ? "" : text.trim();
        }
        return text.replace("\r\n", "\n").trim();
    }
}
