package com.lianyu.common.i18n;

import java.util.Locale;

public enum OutputLanguage {
    ZH("zh"),
    ZH_TW("zh-TW"),
    JA("ja"),
    EN("en");

    private final String code;

    OutputLanguage(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    public static OutputLanguage fromCode(String raw) {
        if (raw == null || raw.isBlank()) {
            return ZH;
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "zh-tw", "zh_hant", "zh-hant", "zh_hk", "zh-hk" -> ZH_TW;
            case "ja", "jp", "japanese" -> JA;
            case "en", "english" -> EN;
            default -> ZH;
        };
    }
}
