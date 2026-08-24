package com.lianyu.service.tools.bridge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;

/**
 * 把 Spring AI 下发的 toolInput 收成引擎认识的 {@code instruction}。
 * 模型常写成 task/query，或直接丢一句中文；旧客户端 JSON.parse 失败会变成 {@code {}}，
 * 引擎 1 秒内回「缺少 instruction」，日志里看不到 computer_task start。
 */
final class AgentToolArguments {

    private static final List<String> INSTRUCTION_KEYS =
            List.of("instruction", "task", "query", "prompt", "input", "text");
    private static final int PREVIEW_CHARS = 160;

    private AgentToolArguments() {
    }

    static String normalizeJson(String raw, ObjectMapper mapper) {
        if (raw == null || raw.isBlank()) {
            return "{}";
        }
        String trimmed = raw.trim();
        try {
            JsonNode node = mapper.readTree(trimmed);
            if (node != null && node.isTextual()) {
                String text = node.asText("").trim();
                return text.isEmpty() ? "{}" : mapper.writeValueAsString(
                        mapper.createObjectNode().put("instruction", text));
            }
            if (node != null && node.isObject()) {
                ObjectNode obj = (ObjectNode) node;
                String instruction = firstInstruction(obj);
                if (instruction != null && !obj.hasNonNull("instruction")) {
                    obj.put("instruction", instruction);
                }
                return mapper.writeValueAsString(obj);
            }
        } catch (Exception ignored) {
            // 非 JSON：整句当作任务
        }
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            return trimmed;
        }
        try {
            return mapper.writeValueAsString(mapper.createObjectNode().put("instruction", trimmed));
        } catch (Exception e) {
            return "{}";
        }
    }

    static String preview(String raw) {
        if (raw == null) {
            return "null";
        }
        String flat = raw.replace('\r', ' ').replace('\n', ' ').trim();
        if (flat.length() > PREVIEW_CHARS) {
            return flat.substring(0, PREVIEW_CHARS) + "…";
        }
        return flat;
    }

    private static String firstInstruction(ObjectNode obj) {
        for (String key : INSTRUCTION_KEYS) {
            JsonNode value = obj.get(key);
            if (value != null && value.isTextual()) {
                String text = value.asText("").trim();
                if (!text.isEmpty()) {
                    return text;
                }
            }
        }
        return null;
    }
}
