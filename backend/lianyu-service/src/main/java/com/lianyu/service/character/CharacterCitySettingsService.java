package com.lianyu.service.character;

import cn.hutool.core.util.StrUtil;
import com.lianyu.common.base.ErrorCode;
import com.lianyu.common.exception.BusinessException;
import com.lianyu.service.ai.AiChatService;
import java.util.Map;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 角色 settings 中的城市模式：现实城市（用户填写）或虚构城市（模型推断）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CharacterCitySettingsService {

    public static final String MODE_REAL = "real";
    public static final String MODE_FICTIONAL = "fictional";
    public static final int MAX_REAL_CITY_CHARS = 50;

    private static final Pattern CONTROL_CHARS = Pattern.compile("[\\p{Cntrl}&&[^\r\n\t]]");

    private final AiChatService aiChatService;

    public void applyCityMode(Long userId, String characterName, String promptTemplate, Map<String, Object> settings) {
        if (settings == null) {
            return;
        }
        String mode = resolveCityMode(settings);
        if (MODE_FICTIONAL.equals(mode)) {
            validateRealCityAbsent(settings);
            settings.remove("fictional_city");
            String inferred = aiChatService.inferFictionalCity(userId, characterName, promptTemplate);
            if (StrUtil.isNotBlank(inferred)) {
                settings.put("fictional_city", inferred.trim());
            } else {
                log.warn("Fictional city inference empty: userId={}, name={}", userId, characterName);
                throw new BusinessException(ErrorCode.AI_PROVIDER_ERROR, "由于角色背景原因虚构失败，建议您选择现实城市");
            }
            settings.remove("city");
            settings.put("city_mode", MODE_FICTIONAL);
            return;
        }

        String city = settings.get("city") instanceof String s ? s.trim() : "";
        if (city.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "请填写你的所在城市");
        }
        settings.put("city", city);
        settings.remove("fictional_city");
        settings.put("city_mode", MODE_REAL);
    }

    public void applySquareAddCity(Long userId,
                                   String characterName,
                                   String promptTemplate,
                                   Map<String, Object> settings,
                                   String cityMode,
                                   String userCity) {
        if (settings == null) {
            settings = new java.util.LinkedHashMap<>();
        }
        String mode = normalizeMode(cityMode);
        settings.put("city_mode", mode);
        if (MODE_FICTIONAL.equals(mode)) {
            settings.remove("city");
            settings.remove("fictional_city");
            applyCityMode(userId, characterName, promptTemplate, settings);
            return;
        }
        if (StrUtil.isBlank(userCity)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "请填写你的所在城市");
        }
        settings.put("city", userCity.trim());
        settings.remove("fictional_city");
        settings.put("city_mode", MODE_REAL);
    }

    public static String resolveRealCity(Map<String, Object> settings) {
        if (settings == null) {
            return "";
        }
        Object city = settings.get("city");
        return city instanceof String s ? s.trim() : "";
    }

    public static String normalizeRealCity(Object raw) {
        if (!(raw instanceof String s)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "请填写你的所在城市");
        }
        String cleaned = CONTROL_CHARS.matcher(s).replaceAll("").trim();
        if (cleaned.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "请填写你的所在城市");
        }
        if (cleaned.length() > MAX_REAL_CITY_CHARS) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "城市名称过长");
        }
        return cleaned;
    }

    public static boolean isRealCityChanged(String previous, String current) {
        if (StrUtil.isBlank(previous) || StrUtil.isBlank(current)) {
            return false;
        }
        return !previous.trim().equalsIgnoreCase(current.trim());
    }

    /**
     * 设置页更新：仅现实城市模式可改 city；虚构模式拒绝写入。
     */
    public void applySettingsCityUpdate(Map<String, Object> mergedSettings, Map<String, Object> patch) {
        if (mergedSettings == null || patch == null || !patch.containsKey("city")) {
            return;
        }
        String mode = resolveCityMode(mergedSettings);
        if (MODE_FICTIONAL.equals(mode)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "虚构城市角色无法修改现实城市");
        }
        String city = normalizeRealCity(patch.get("city"));
        mergedSettings.put("city", city);
        mergedSettings.put("city_mode", MODE_REAL);
        mergedSettings.remove("fictional_city");
    }

    public static String resolveCityMode(Map<String, Object> settings) {
        if (settings == null) {
            return MODE_REAL;
        }
        String mode = normalizeMode(settings.get("city_mode") instanceof String s ? s : null);
        if (MODE_FICTIONAL.equals(mode)) {
            return MODE_FICTIONAL;
        }
        Object legacy = settings.get("use_fictional_city");
        if (Boolean.TRUE.equals(legacy) || "true".equalsIgnoreCase(String.valueOf(legacy))) {
            return MODE_FICTIONAL;
        }
        return MODE_REAL;
    }

    private static String normalizeMode(String cityMode) {
        if (cityMode != null && MODE_FICTIONAL.equalsIgnoreCase(cityMode.trim())) {
            return MODE_FICTIONAL;
        }
        return MODE_REAL;
    }

    private static void validateRealCityAbsent(Map<String, Object> settings) {
        Object city = settings.get("city");
        if (city instanceof String s && !s.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "选择虚构城市时无需填写现实城市");
        }
    }
}
