package com.lianyu.web.agent;

import java.util.List;

/**
 * Python 侧 SSE 帧解析器（纯函数，便于单测）。
 *
 * <p>契约（与 Python `app/stream_chat.py` 一致）：
 * <ul>
 *   <li>{@code data: {"content":"..."}} —— token 增量</li>
 *   <li>{@code data: {"replace":"..."}} —— 语言门整体替换</li>
 *   <li>{@code data: {"error":"..."}} —— 错误收尾</li>
 *   <li>{@code data: [DONE]} —— 终止</li>
 *   <li>{@code : xxx} —— 心跳注释行，忽略</li>
 * </ul>
 *
 * <p>注意：**不解析 pieces** —— 多气泡切分仍由 Java 的 AssistantReplySplitter 在
 * beforeStreamComplete 阶段完成，跨端边界不允许漂移。
 */
public final class AgentSseParser {

    /** 单帧解析结果。字段互斥，按 type 判定。 */
    public record Event(Type type, String text) {
        public enum Type { CONTENT, REPLACE, ERROR, DONE, IGNORED }
    }

    private AgentSseParser() {
    }

    /**
     * 解析一行（已去掉换行符）。
     *
     * @return 解析结果；空行与心跳返回 IGNORED
     */
    public static Event parseLine(String line) {
        if (line == null) {
            return new Event(Event.Type.IGNORED, null);
        }
        String trimmed = line.trim();
        if (trimmed.isEmpty() || trimmed.startsWith(":")) {
            return new Event(Event.Type.IGNORED, null);
        }
        if (!trimmed.startsWith("data:")) {
            return new Event(Event.Type.IGNORED, null);
        }
        String data = trimmed.substring("data:".length()).trim();
        if (data.isEmpty()) {
            return new Event(Event.Type.IGNORED, null);
        }
        if ("[DONE]".equals(data)) {
            return new Event(Event.Type.DONE, null);
        }
        if (!data.startsWith("{")) {
            // 非 JSON 载荷（如上游异常的纯文本）视为忽略，绝不抛异常打断流
            return new Event(Event.Type.IGNORED, null);
        }
        return parseJson(data);
    }

    /** 批量解析（供测试与离线排查）。 */
    public static List<Event> parseAll(Iterable<String> lines) {
        List<Event> events = new java.util.ArrayList<>();
        for (String line : lines) {
            Event event = parseLine(line);
            if (event.type() != Event.Type.IGNORED) {
                events.add(event);
            }
        }
        return events;
    }

    private static Event parseJson(String data) {
        // 极简 JSON 取值：避免为三字段引入 ObjectMapper 依赖到纯函数里；
        // 但必须正确处理转义，故交给调用方注入的 ObjectMapper 不合适 —— 这里用字符串扫描。
        String content = extractString(data, "content");
        if (content != null) {
            return new Event(Event.Type.CONTENT, content);
        }
        String replace = extractString(data, "replace");
        if (replace != null) {
            return new Event(Event.Type.REPLACE, replace);
        }
        String error = extractString(data, "error");
        if (error != null) {
            return new Event(Event.Type.ERROR, error);
        }
        return new Event(Event.Type.IGNORED, null);
    }

    /**
     * 从 JSON 文本里取字符串字段值（含 "\\n" / "\\"" / "\\\\" / "\\uXXXX" 反转义）。
     * 返回 null 表示字段不存在或为 null。
     */
    static String extractString(String json, String field) {
        String needle = "\"" + field + "\"";
        int keyIdx = json.indexOf(needle);
        if (keyIdx < 0) {
            return null;
        }
        int colon = json.indexOf(':', keyIdx + needle.length());
        if (colon < 0) {
            return null;
        }
        int i = colon + 1;
        while (i < json.length() && Character.isWhitespace(json.charAt(i))) {
            i++;
        }
        if (i >= json.length() || json.charAt(i) != '"') {
            return null;  // null / 数字 / 对象 —— 本契约只接受字符串
        }
        StringBuilder sb = new StringBuilder();
        i++;
        while (i < json.length()) {
            char c = json.charAt(i);
            if (c == '\\') {
                if (i + 1 >= json.length()) {
                    return sb.toString();
                }
                char esc = json.charAt(i + 1);
                switch (esc) {
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'u' -> {
                        if (i + 5 >= json.length()) {
                            return sb.toString();
                        }
                        String hex = json.substring(i + 2, i + 6);
                        try {
                            sb.append((char) Integer.parseInt(hex, 16));
                        } catch (NumberFormatException e) {
                            return sb.toString();
                        }
                        i += 4;
                    }
                    default -> sb.append(esc);
                }
                i += 2;
                continue;
            }
            if (c == '"') {
                return sb.toString();
            }
            sb.append(c);
            i++;
        }
        return sb.toString();
    }
}
