package com.lianyu.service.character;

import cn.hutool.core.util.StrUtil;
import com.lianyu.common.base.ErrorCode;
import com.lianyu.common.exception.BusinessException;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * 角色 settings 中的城市：仅支持用户填写的现实城市。
 * （虚构城市模式已下线；历史 fictional 角色仍可读，保存城市时自动切回 real。）
 */
@Service
public class CharacterCitySettingsService {

    public static final String MODE_REAL = "real";
    /** @deprecated 虚构城市已下线；仅用于识别历史数据 */
    @Deprecated
    public static final String MODE_FICTIONAL = "fictional";
    public static final int MAX_REAL_CITY_CHARS = 50;

    private static final Pattern CONTROL_CHARS = Pattern.compile("[\\p{Cntrl}&&[^\r\n\t]]");

    /** 创建角色：强制现实城市。 */
    public void applyCityMode(Long userId, String characterName, String promptTemplate, Map<String, Object> settings) {
        if (settings == null) {
            return;
        }
        String city = normalizeRealCity(settings.get("city"));
        settings.put("city", city);
        settings.remove("fictional_city");
        settings.remove("use_fictional_city");
        settings.put("city_mode", MODE_REAL);
    }

    /** 广场添加：强制现实城市。 */
    public void applySquareAddCity(Long userId,
                                   String characterName,
                                   String promptTemplate,
                                   Map<String, Object> settings,
                                   String cityMode,
                                   String userCity) {
        if (settings == null) {
            settings = new java.util.LinkedHashMap<>();
        }
        if (StrUtil.isBlank(userCity)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "请填写你的所在城市");
        }
        settings.put("city", normalizeRealCity(userCity));
        settings.remove("fictional_city");
        settings.remove("use_fictional_city");
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
     * 设置页更新城市：始终写入现实城市（历史虚构角色保存时一并切回 real）。
     */
    public void applySettingsCityUpdate(Map<String, Object> mergedSettings, Map<String, Object> patch) {
        if (mergedSettings == null || patch == null || !patch.containsKey("city")) {
            return;
        }
        String city = normalizeRealCity(patch.get("city"));
        mergedSettings.put("city", city);
        mergedSettings.put("city_mode", MODE_REAL);
        mergedSettings.remove("fictional_city");
        mergedSettings.remove("use_fictional_city");
    }

    public static String resolveCityMode(Map<String, Object> settings) {
        if (settings == null) {
            return MODE_REAL;
        }
        Object modeObj = settings.get("city_mode");
        if (modeObj instanceof String s && MODE_FICTIONAL.equalsIgnoreCase(s.trim())) {
            return MODE_FICTIONAL;
        }
        Object legacy = settings.get("use_fictional_city");
        if (Boolean.TRUE.equals(legacy) || "true".equalsIgnoreCase(String.valueOf(legacy))) {
            return MODE_FICTIONAL;
        }
        return MODE_REAL;
    }
}
