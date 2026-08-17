package com.lianyu.service.character;

import com.lianyu.common.base.ErrorCode;
import com.lianyu.common.exception.BusinessException;
import java.util.regex.Pattern;

/**
 * 导入人设/聊天记录：清洗后截取尾部（聊天记录后半段更能代表当前性格）。
 */
public final class CharacterImportSourceParser {

    public static final int MAX_CHARS = 12_000;
    public static final int MAX_RAW_CHARS = 100_000;

    private static final Pattern CONTROL_CHARS = Pattern.compile("[\\p{Cntrl}&&[^\r\n\t]]");
    private static final Pattern HTML_BLOCK = Pattern.compile(
            "(?is)<script[^>]*>.*?</script>|<style[^>]*>.*?</style>|<[^>]+>");
    private static final Pattern MULTI_SPACE = Pattern.compile("[ \\t]{3,}");
    private static final Pattern MULTI_NEWLINE = Pattern.compile("\\n{3,}");

    private CharacterImportSourceParser() {
    }

    public static String prepare(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "请提供人设或聊天记录");
        }
        if (raw.length() > MAX_RAW_CHARS) {
            throw new BusinessException(ErrorCode.FILE_TOO_LARGE, "人设或聊天记录过长，请截取后再导入");
        }
        String text = CONTROL_CHARS.matcher(raw).replaceAll("");
        if (looksLikeHtml(text)) {
            text = HTML_BLOCK.matcher(text).replaceAll(" ");
            text = decodeBasicEntities(text);
        }
        text = text.replace("\r\n", "\n").replace('\r', '\n');
        text = MULTI_SPACE.matcher(text).replaceAll("  ");
        text = MULTI_NEWLINE.matcher(text).replaceAll("\n\n");
        text = text.trim();
        if (text.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "请提供人设或聊天记录");
        }
        if (text.length() > MAX_CHARS) {
            text = text.substring(text.length() - MAX_CHARS).trim();
        }
        return text;
    }

    public static String wrapForModel(String prepared) {
        String body = prepared == null ? "" : prepared
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
        return "<user_message trusted=\"false\">\n" + body + "\n</user_message>";
    }

    private static boolean looksLikeHtml(String text) {
        String lower = text.toLowerCase();
        return lower.contains("<html") || lower.contains("<body") || lower.contains("<div")
                || lower.contains("<p>") || lower.contains("<br");
    }

    private static String decodeBasicEntities(String text) {
        return text.replace("&nbsp;", " ")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&amp;", "&")
                .replace("&#39;", "'")
                .replace("&quot;", "\"");
    }
}
