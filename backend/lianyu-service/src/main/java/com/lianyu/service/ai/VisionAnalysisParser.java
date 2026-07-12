package com.lianyu.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class VisionAnalysisParser {

    private static final Pattern JSON_TAG =
            Pattern.compile("^<json>\\s*(\\{.*})\\s*</json>$", Pattern.DOTALL);
    private static final Pattern JSON_FENCE =
            Pattern.compile("^```json\\s*(\\{.*})\\s*```$", Pattern.DOTALL);

    private final ObjectMapper objectMapper;

    VisionAnalysisParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    VisionAnalysisResult parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Vision analysis payload must be JSON");
        }

        String json = unwrap(raw.trim());
        try {
            JsonNode node = objectMapper.readTree(json);
            if (node == null || !node.isObject()) {
                throw new IllegalArgumentException("Vision analysis payload must be a JSON object");
            }
            return new VisionAnalysisResult(
                    textOrNull(node, "subIntent"),
                    textOrNull(node, "confidence"),
                    textOrNull(node, "imageDescription"));
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid vision analysis JSON", e);
        }
    }

    static boolean isLowConfidence(String confidence) {
        if (confidence == null) {
            return false;
        }
        String normalized = confidence.toLowerCase();
        return normalized.contains("low")
                || normalized.contains("不清")
                || normalized.contains("模糊")
                || normalized.contains("无法")
                || normalized.contains("看不清");
    }

    private static String unwrap(String input) {
        Matcher matcher = JSON_TAG.matcher(input);
        if (matcher.matches()) {
            return matcher.group(1).trim();
        }

        matcher = JSON_FENCE.matcher(input);
        if (matcher.matches()) {
            return matcher.group(1).trim();
        }

        if (input.startsWith("{")) {
            return input;
        }
        throw new IllegalArgumentException("Vision analysis payload must be JSON");
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && !value.isNull() ? value.asText() : null;
    }
}
