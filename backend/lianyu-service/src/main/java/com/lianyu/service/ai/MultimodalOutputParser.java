package com.lianyu.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 解析多模态模型一次调用产出的「结构化JSON + 角色回复」混合输出。
 *
 * <p>输出约定：模型先输出一个 JSON 块（sub_intent / confidence / image_description），
 * 再以角色身份输出最终回复。JSON 块优先用 {@code <json>...</json>} 标签包裹，
 * 兼容 {@code ```json} 围栏与裸 {@code {...}}。解析失败时整体视作回复，保证可用。
 */
final class MultimodalOutputParser {

    private static final Pattern JSON_TAG =
            Pattern.compile("<json>\\s*(\\{.*?})\\s*</json>", Pattern.DOTALL);
    private static final Pattern JSON_FENCE =
            Pattern.compile("```json\\s*(\\{.*?})\\s*```", Pattern.DOTALL);
    private static final Pattern JSON_BRACE =
            Pattern.compile("(\\{[^{}]*})", Pattern.DOTALL);

    private final ObjectMapper objectMapper;

    MultimodalOutputParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    record Result(String reply, String subIntent, String confidence, String imageDescription) {
    }

    Result parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return new Result("", null, null, null);
        }
        String trimmed = raw.trim();

        MatchedJson matched = extractJson(trimmed);
        if (matched == null) {
            return new Result(trimmed, null, null, null);
        }

        JsonNode node;
        try {
            node = objectMapper.readTree(matched.json());
        } catch (Exception e) {
            return new Result(trimmed, null, null, null);
        }

        String reply = matched.end() < trimmed.length()
                ? trimmed.substring(matched.end()).trim()
                : "";
        return new Result(
                reply,
                textOrNull(node, "sub_intent"),
                textOrNull(node, "confidence"),
                textOrNull(node, "image_description"));
    }

    /** 置信度低 = 模型看不清图片，应提示用户。 */
    static boolean isLowConfidence(String confidence) {
        if (confidence == null) {
            return false;
        }
        String c = confidence.toLowerCase();
        return c.contains("low")
                || c.contains("不清")
                || c.contains("模糊")
                || c.contains("无法")
                || c.contains("看不清");
    }

    private static MatchedJson extractJson(String input) {
        Matcher m = JSON_TAG.matcher(input);
        if (m.find()) {
            return new MatchedJson(m.group(1), m.end());
        }
        m = JSON_FENCE.matcher(input);
        if (m.find()) {
            return new MatchedJson(m.group(1), m.end());
        }
        m = JSON_BRACE.matcher(input);
        if (m.find()) {
            return new MatchedJson(m.group(1), m.end());
        }
        return null;
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return (v != null && !v.isNull()) ? v.asText() : null;
    }

    private record MatchedJson(String json, int end) {
    }
}
