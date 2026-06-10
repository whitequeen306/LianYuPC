package com.lianyu.service.character;

import cn.hutool.core.util.StrUtil;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 解析对用户的称呼：优先长期记忆姓名，否则用户昵称，最后兜底「你」。
 */
public final class UserAddressingResolver {

    private static final Pattern PROFILE_NAME = Pattern.compile("【长期记忆/姓名】\\s*([^\\n]+)");

    private UserAddressingResolver() {
    }

    public static String resolve(String memoryContext, String nicknameFallback) {
        String fromMemory = extractProfileName(memoryContext);
        if (StrUtil.isNotBlank(fromMemory)) {
            return fromMemory.trim();
        }
        if (StrUtil.isNotBlank(nicknameFallback)) {
            return nicknameFallback.trim();
        }
        return "你";
    }

    private static String extractProfileName(String memoryContext) {
        if (memoryContext == null || memoryContext.isBlank()) {
            return null;
        }
        Matcher matcher = PROFILE_NAME.matcher(memoryContext);
        if (!matcher.find()) {
            return null;
        }
        String name = matcher.group(1).trim();
        return name.isBlank() ? null : name;
    }
}
