package com.lianyu.service.character;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lianyu.common.base.ErrorCode;
import com.lianyu.common.exception.BusinessException;
import com.lianyu.common.util.CharacterSettingsUtils;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 将模型输出解析为固定字段的角色导入草稿。多余字段丢弃。
 */
public final class CharacterImportDraftMapper {

    private static final List<String> SPEAKING_STYLES =
            List.of("温柔", "活泼", "冷静", "傲娇", "元气", "慵懒", "成熟", "毒舌");
    private static final Set<String> ARCHETYPES =
            Set.of("gentle", "tsundere", "yandere", "genki", "onesan", "oc");
    private static final Set<String> SOURCE_TYPES = Set.of("persona", "chat_log", "mixed");

    private CharacterImportDraftMapper() {
    }

    public static Map<String, Object> parse(ObjectMapper objectMapper, String llmContent) {
        if (llmContent == null || llmContent.isBlank()) {
            throw new BusinessException(ErrorCode.AI_PROVIDER_ERROR, "抽取结果为空，请换一份材料再试");
        }
        String json = extractJsonObject(llmContent.trim());
        JsonNode root;
        try {
            root = objectMapper.readTree(json);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.AI_PROVIDER_ERROR, "抽取结果格式异常，请重试");
        }
        if (root == null || !root.isObject()) {
            throw new BusinessException(ErrorCode.AI_PROVIDER_ERROR, "抽取结果格式异常，请重试");
        }

        String name = fix(valueOrDefault(root, "name", "未命名角色"));
        String age = fix(valueOrDefault(root, "age", "未知"));
        String gender = normalizeGender(fix(valueOrDefault(root, "gender", "未知")));
        String speakingStyle = normalizeSpeakingStyle(fix(valueOrDefault(root, "speakingStyle", "温柔")));
        String archetype = normalizeArchetype(valueOrDefault(root, "personalityArchetype", "oc"));
        String sourceType = normalizeSourceType(valueOrDefault(root, "sourceType", "persona"));
        String summary = fix(valueOrDefault(root, "summary", ""));
        String userAddressing = CharacterAddressing.sanitize(valueOrDefault(root, "userAddressing", ""));
        String promptTemplate = fix(valueOrDefault(root, "promptTemplate", ""));
        if (promptTemplate.isBlank()) {
            promptTemplate = "性格定位：自设（oc）\n你是" + name + "。用自然口语和用户私聊，保持自己的性格。";
        }

        Map<String, Object> draft = new LinkedHashMap<>();
        draft.put("sourceType", sourceType);
        draft.put("name", name);
        draft.put("age", age);
        draft.put("gender", gender);
        draft.put("speakingStyle", speakingStyle);
        draft.put("personalityArchetype", archetype);
        draft.put("userAddressing", userAddressing);
        draft.put("promptTemplate", promptTemplate);
        draft.put("summary", summary);
        return draft;
    }

    static String normalizeSpeakingStyle(String raw) {
        if (raw == null || raw.isBlank()) {
            return "温柔";
        }
        String text = raw.trim();
        for (String style : SPEAKING_STYLES) {
            if (text.equals(style) || text.contains(style)) {
                return style;
            }
        }
        return "温柔";
    }

    private static String normalizeGender(String raw) {
        if (raw.contains("女")) {
            return "女";
        }
        if (raw.contains("男")) {
            return "男";
        }
        if (raw.contains("其他")) {
            return "其他";
        }
        return "未知";
    }

    private static String normalizeArchetype(String raw) {
        if (raw == null) {
            return "oc";
        }
        String key = raw.trim().toLowerCase();
        return ARCHETYPES.contains(key) ? key : "oc";
    }

    private static String normalizeSourceType(String raw) {
        if (raw == null) {
            return "persona";
        }
        String key = raw.trim().toLowerCase();
        return SOURCE_TYPES.contains(key) ? key : "persona";
    }

    private static String valueOrDefault(JsonNode root, String field, String defaultValue) {
        JsonNode node = root.get(field);
        if (node == null || node.isNull()) {
            return defaultValue;
        }
        String value = node.asText("");
        return value.isBlank() ? defaultValue : value.trim();
    }

    private static String fix(String value) {
        return CharacterSettingsUtils.fixUtf8Mojibake(value);
    }

    private static String extractJsonObject(String text) {
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return text;
    }
}
