package com.lianyu.service.character;

/**
 * 角色对用户最常用的称呼：口吻参考，不是每句必喊。
 */
public final class CharacterAddressing {

    public static final int MAX_CHARS = 32;

    private CharacterAddressing() {
    }

    public static String sanitize(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (!Character.isISOControl(c)) {
                sb.append(c);
            }
        }
        String trimmed = sb.toString().trim()
                .replace("「", "")
                .replace("」", "")
                .replace("\"", "")
                .replace("'", "")
                .replace("“", "")
                .replace("”", "");
        trimmed = trimmed.trim();
        if (trimmed.length() > MAX_CHARS) {
            return trimmed.substring(0, MAX_CHARS);
        }
        return trimmed;
    }

    public static String hintBlock(String addressing) {
        String trimmed = sanitize(addressing);
        if (trimmed.isEmpty()) {
            return "";
        }
        return "\n\n对用户最常用的称呼是「" + trimmed + "」。这是口吻参考，不是每句都必须这样叫。";
    }

    public static String appendHint(String promptTemplate, String addressing) {
        String base = promptTemplate == null ? "" : promptTemplate;
        String trimmed = sanitize(addressing);
        if (trimmed.isEmpty()) {
            return base;
        }
        String marker = "最常用的称呼是「" + trimmed + "」";
        if (base.contains(marker)) {
            return base;
        }
        return base + hintBlock(trimmed);
    }
}
