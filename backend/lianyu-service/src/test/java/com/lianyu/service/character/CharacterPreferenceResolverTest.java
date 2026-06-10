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
}
