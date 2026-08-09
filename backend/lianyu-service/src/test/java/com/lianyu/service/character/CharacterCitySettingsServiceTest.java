package com.lianyu.service.character;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lianyu.common.exception.BusinessException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CharacterCitySettingsServiceTest {

    private final CharacterCitySettingsService service = new CharacterCitySettingsService();

    @Test
    void isRealCityChanged_detectsCaseInsensitiveDifference() {
        assertTrue(CharacterCitySettingsService.isRealCityChanged("上海", "北京"));
        assertFalse(CharacterCitySettingsService.isRealCityChanged("上海", "上海"));
        assertFalse(CharacterCitySettingsService.isRealCityChanged("上海", " 上海 "));
        assertFalse(CharacterCitySettingsService.isRealCityChanged("", "北京"));
        assertFalse(CharacterCitySettingsService.isRealCityChanged("上海", ""));
    }

    @Test
    void normalizeRealCity_stripsControlCharsAndTrims() {
        assertEquals("北京", CharacterCitySettingsService.normalizeRealCity("  北京 \u0007 "));
    }

    @Test
    void normalizeRealCity_rejectsBlank() {
        assertThrows(BusinessException.class, () -> CharacterCitySettingsService.normalizeRealCity("   "));
    }

    @Test
    void applySettingsCityUpdate_convertsLegacyFictionalToReal() {
        Map<String, Object> merged = new LinkedHashMap<>();
        merged.put("city_mode", CharacterCitySettingsService.MODE_FICTIONAL);
        merged.put("fictional_city", "天宫市");
        Map<String, Object> patch = Map.of("city", "北京");

        service.applySettingsCityUpdate(merged, patch);

        assertEquals("北京", merged.get("city"));
        assertEquals(CharacterCitySettingsService.MODE_REAL, merged.get("city_mode"));
        assertFalse(merged.containsKey("fictional_city"));
    }

    @Test
    void applySettingsCityUpdate_writesCityForRealMode() {
        Map<String, Object> merged = new LinkedHashMap<>();
        merged.put("city_mode", CharacterCitySettingsService.MODE_REAL);
        merged.put("city", "上海");
        Map<String, Object> patch = Map.of("city", "北京");

        service.applySettingsCityUpdate(merged, patch);

        assertEquals("北京", merged.get("city"));
        assertEquals(CharacterCitySettingsService.MODE_REAL, merged.get("city_mode"));
        assertFalse(merged.containsKey("fictional_city"));
    }

    @Test
    void applySquareAddCity_alwaysRequiresRealCity() {
        Map<String, Object> settings = new LinkedHashMap<>();
        service.applySquareAddCity(1L, "测试", "人设", settings, "fictional", "上海");

        assertEquals("上海", settings.get("city"));
        assertEquals(CharacterCitySettingsService.MODE_REAL, settings.get("city_mode"));
        assertFalse(settings.containsKey("fictional_city"));
    }

    @Test
    void applySquareAddCity_rejectsBlankCity() {
        Map<String, Object> settings = new LinkedHashMap<>();
        assertThrows(BusinessException.class,
                () -> service.applySquareAddCity(1L, "测试", "人设", settings, "real", "  "));
    }
}
