package com.lianyu.service.graph;

/**
 * 历史消息中的图片占位：文本聊天只送文字，不送 imageUrl / 二进制。
 */
public final class ImageMessageHistoryText {

    private static final String GENERIC_STORED = "（用户发送了一张图片）";
    private static final String GENERIC_SHORT = "（用户发了一张图片）";

    private ImageMessageHistoryText() {
    }

    public static String placeholder(String imageDescription) {
        String desc = trimToNull(imageDescription);
        if (desc == null) {
            return GENERIC_SHORT;
        }
        return "（用户发了一张图片（" + desc + "））";
    }

    /**
     * 将 DB 中的用户消息转为可送入文本模型的历史句（无 imageUrl）。
     */
    public static String forHistory(String storedContent, boolean hasImage) {
        if (!hasImage) {
            return storedContent;
        }
        String existing = storedContent != null ? storedContent.trim() : "";
        String placeholder = extractEmbeddedPlaceholder(existing);
        if (placeholder != null) {
            return placeholder;
        }
        String desc = extractDescriptionFromModelText(existing);
        placeholder = placeholder(desc);
        if (existing.isEmpty() || isGenericStoredPlaceholder(existing)) {
            return placeholder;
        }
        if (existing.contains(placeholder)) {
            return existing;
        }
        return existing + "\n" + placeholder;
    }

    /**
     * 识图完成后写回 DB 的<strong>模型历史</strong>文案（进 contextContent，不直接展示给用户）。
     */
    public static String forPersist(String userCaption, String imageDescription) {
        String caption = userCaption != null ? userCaption.trim() : "";
        String placeholder = placeholder(imageDescription);
        if (caption.isEmpty() || isGenericStoredPlaceholder(caption)) {
            return placeholder;
        }
        if (caption.contains("（用户发了一张图片")) {
            return caption;
        }
        return caption + "\n" + placeholder;
    }

    /**
     * 用户气泡可见文案：仅保留用户原话；纯图用通用占位（前端有图时会隐藏）。
     */
    public static String forUserVisible(String userCaption) {
        String caption = userCaption != null ? userCaption.trim() : "";
        if (caption.isEmpty() || isGenericStoredPlaceholder(caption)) {
            return GENERIC_STORED;
        }
        return forDisplay(caption);
    }

    /**
     * 从已污染的 content 里剥掉内部识图占位，只留用户说的话。
     */
    public static String forDisplay(String storedContent) {
        if (storedContent == null || storedContent.isBlank()) {
            return "";
        }
        String s = storedContent;
        s = s.replaceAll("\\n?（用户发了一张图片（[^）]*））", "");
        s = s.replace(GENERIC_SHORT, "");
        s = s.replace(GENERIC_STORED, "");
        s = s.replace("用户发送了一张图片", "");
        return s.trim();
    }

    public static boolean isGenericStoredPlaceholder(String content) {
        if (content == null || content.isBlank()) {
            return true;
        }
        String trimmed = content.trim();
        return GENERIC_STORED.equals(trimmed)
                || GENERIC_SHORT.equals(trimmed)
                || trimmed.equals("用户发送了一张图片")
                || isEmptyUserMessageXml(trimmed);
    }

    private static String extractEmbeddedPlaceholder(String content) {
        if (content == null || !content.contains("（用户发了一张图片")) {
            return null;
        }
        java.util.regex.Matcher withDesc = java.util.regex.Pattern
                .compile("（用户发了一张图片（[^）]+））")
                .matcher(content);
        if (withDesc.find()) {
            return withDesc.group();
        }
        if (content.contains(GENERIC_SHORT)) {
            return GENERIC_SHORT;
        }
        return null;
    }

    private static String extractDescriptionFromModelText(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        String stripped = content.replaceAll("(?s)<user_message[^>]*>|</user_message>", "").trim();
        var matcher = java.util.regex.Pattern.compile("图片内容[：:]\\s*(.+?)(?:\\n|$)")
                .matcher(stripped);
        if (matcher.find()) {
            return trimToNull(matcher.group(1));
        }
        return null;
    }

    private static boolean isEmptyUserMessageXml(String content) {
        String inner = content.replaceAll("(?s)<user_message[^>]*>|</user_message>", "").trim();
        return inner.isEmpty();
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
