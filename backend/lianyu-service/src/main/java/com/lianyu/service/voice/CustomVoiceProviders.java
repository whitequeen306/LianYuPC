package com.lianyu.service.voice;

import java.util.Locale;
import java.util.Set;

public final class CustomVoiceProviders {
    public static final String DASHSCOPE_VC = "DASHSCOPE_VC";
    public static final String GPTSOVITS_LOCAL = "GPTSOVITS_LOCAL";

    public static final Set<String> ALL = Set.of(DASHSCOPE_VC, GPTSOVITS_LOCAL);

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_READY = "READY";
    public static final String STATUS_FAILED = "FAILED";

    private CustomVoiceProviders() {
    }

    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String p = raw.trim().toUpperCase(Locale.ROOT);
        return ALL.contains(p) ? p : null;
    }
}
