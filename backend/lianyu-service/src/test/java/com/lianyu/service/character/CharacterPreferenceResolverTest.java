package com.lianyu.service.character;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class CharacterPreferenceResolverTest {

    @Test
    void doNotDisturb_settingDefaultsToEnabledWhenMissing() {
        assertTrue(CharacterPreferenceResolver.resolveBoolean(
                null, CharacterPreferenceResolver.KEY_DO_NOT_DISTURB, true));
        assertTrue(CharacterPreferenceResolver.resolveBoolean(
                Map.of(), CharacterPreferenceResolver.KEY_DO_NOT_DISTURB, true));
    }

    @Test
    void doNotDisturb_respectsExplicitDisable() {
        assertFalse(CharacterPreferenceResolver.isDoNotDisturbActive(Map.of("doNotDisturbEnabled", false)));
    }

    @Test
    void showInnerThoughts_defaultsToTrue() {
        assertTrue(CharacterPreferenceResolver.showInnerThoughts((Map<String, Object>) null));
        assertTrue(CharacterPreferenceResolver.showInnerThoughts(Map.of()));
    }

    @Test
    void showInnerThoughts_respectsExplicitDisable() {
        assertFalse(CharacterPreferenceResolver.showInnerThoughts(Map.of("showInnerThoughts", false)));
    }

    @Test
    void creationDefaultsNoLongerSeedDesktopFlag() {
        // 桌面能力层已下线：新建角色不该再写这个键（老角色 JSON 里残留的键不迁移、不读取）
        Map<String, Object> settings = new java.util.HashMap<>();
        CharacterPreferenceResolver.applyCreationDefaults(settings);
        assertFalse(settings.containsKey("desktopEnabled"));
        assertTrue(settings.containsKey(CharacterPreferenceResolver.KEY_DO_NOT_DISTURB));
        assertTrue(settings.containsKey(CharacterPreferenceResolver.KEY_SHOW_INNER_THOUGHTS));
    }
}
