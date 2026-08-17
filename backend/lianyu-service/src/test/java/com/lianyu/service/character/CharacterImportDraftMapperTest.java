package com.lianyu.service.character;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lianyu.common.exception.BusinessException;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CharacterImportDraftMapperTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void mapsFixedJsonAndNormalizesEnums() throws Exception {
        String raw = """
                这是说明
                {
                  "sourceType": "chat_log",
                  "name": "时崎狂三",
                  "age": "17",
                  "gender": "女",
                  "speakingStyle": "有点傲娇毒舌",
                  "personalityArchetype": "tsundere",
                  "promptTemplate": "性格定位：傲娇（tsundere）\\n嘴硬但会默默关心对方。",
                  "summary": "冷静又别扭的精灵少女"
                }
                """;

        Map<String, Object> draft = CharacterImportDraftMapper.parse(objectMapper, raw);

        assertEquals("chat_log", draft.get("sourceType"));
        assertEquals("时崎狂三", draft.get("name"));
        assertEquals("17", draft.get("age"));
        assertEquals("女", draft.get("gender"));
        assertEquals("傲娇", draft.get("speakingStyle"));
        assertEquals("tsundere", draft.get("personalityArchetype"));
        assertEquals("冷静又别扭的精灵少女", draft.get("summary"));
        assertEquals(true, String.valueOf(draft.get("promptTemplate")).contains("性格定位"));
    }

    @Test
    void defaultsUnknownFieldsAndDropsExtras() throws Exception {
        String raw = """
                {"name":"小白","foo":"bar","speakingStyle":"神秘宇宙语","personalityArchetype":"villain"}
                """;

        Map<String, Object> draft = CharacterImportDraftMapper.parse(objectMapper, raw);

        assertEquals("小白", draft.get("name"));
        assertEquals("未知", draft.get("age"));
        assertEquals("未知", draft.get("gender"));
        assertEquals("温柔", draft.get("speakingStyle"));
        assertEquals("oc", draft.get("personalityArchetype"));
        assertEquals("persona", draft.get("sourceType"));
        assertFalseContains(draft, "foo");
    }

    @Test
    void rejectsNonJson() {
        assertThrows(BusinessException.class,
                () -> CharacterImportDraftMapper.parse(objectMapper, "角色很可爱"));
    }

    private static void assertFalseContains(Map<String, Object> draft, String key) {
        if (draft.containsKey(key)) {
            throw new AssertionError("unexpected extra field: " + key);
        }
    }
}
