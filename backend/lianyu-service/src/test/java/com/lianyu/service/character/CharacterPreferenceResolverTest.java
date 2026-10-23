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
    void desktopEnabled_defaultsToFalse() {
        // 默认必须关：老角色不该因为升级就突然获得操控电脑的权限
        assertFalse(CharacterPreferenceResolver.desktopEnabled((Map<String, Object>) null));
        assertFalse(CharacterPreferenceResolver.desktopEnabled(Map.of()));
    }

    @Test
    void desktopEnabled_respectsExplicitOptIn() {
        assertTrue(CharacterPreferenceResolver.desktopEnabled(Map.of("desktopEnabled", true)));
        assertTrue(CharacterPreferenceResolver.desktopEnabled(Map.of("desktopEnabled", "true")));
        assertFalse(CharacterPreferenceResolver.desktopEnabled(Map.of("desktopEnabled", false)));
    }

    @Test
    void creationDefaultsSeedDesktopOff() {
        Map<String, Object> settings = new java.util.HashMap<>();
        CharacterPreferenceResolver.applyCreationDefaults(settings);
        assertFalse(CharacterPreferenceResolver.desktopEnabled(settings));
    }
}
