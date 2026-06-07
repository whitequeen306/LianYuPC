package com.lianyu.service.moments;

/**
 * Shared whitespace normalization and length limits for moments text surfaces.
 */
final class MomentsTextSanitizer {

    private MomentsTextSanitizer() {
    }

    static String sanitize(String raw, int maxChars, int minChars) {
        String text = truncate(normalizeWhitespace(raw), maxChars);
        if (text.length() < minChars) {
            return "";
        }
        return text;
    }

    static String sanitizeWithoutMin(String raw, int maxChars) {
        return truncate(normalizeWhitespace(raw), maxChars);
    }

    private static String normalizeWhitespace(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.trim()
                .replaceAll("[\\r\\n]+", " ")
                .replaceAll("\\s{2,}", " ");
    }

    private static String truncate(String text, int maxChars) {
        if (text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars);
    }
}
