package com.lianyu.service.community;

import com.lianyu.common.base.ErrorCode;
import com.lianyu.common.exception.BusinessException;
import com.lianyu.common.util.UserInputSanitizer;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Sync rule layer for community UGC (before async model moderation).
 */
public final class CommunityContentRules {

    private static final Pattern BLOCKED = Pattern.compile(
            "(色情|嫖娼|赌博|毒品|枪支|炸弹|自杀教程|血腥屠杀)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private CommunityContentRules() {
    }

    public static String sanitizePostContent(String raw) {
        String cleaned = UserInputSanitizer.sanitizeGenerationDescription(raw == null ? "" : raw).trim();
        if (cleaned.length() > 1000) {
            cleaned = cleaned.substring(0, 1000);
        }
        return cleaned;
    }

    public static String sanitizeCommentContent(String raw) {
        String cleaned = UserInputSanitizer.sanitizeGenerationDescription(raw == null ? "" : raw).trim();
        if (cleaned.length() > 512) {
            cleaned = cleaned.substring(0, 512);
        }
        return cleaned;
    }

    public static void assertPostAllowed(String content, List<String> imageUrls) {
        boolean hasText = content != null && !content.isBlank();
        boolean hasImages = imageUrls != null && !imageUrls.isEmpty();
        if (!hasText && !hasImages) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "请填写文字或上传图片");
        }
        if (imageUrls != null && imageUrls.size() > 9) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "最多上传 9 张图片");
        }
        assertTextClean(content);
    }

    public static void assertCommentAllowed(String content) {
        if (content == null || content.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "评论不能为空");
        }
        assertTextClean(content);
    }

    public static boolean passesSecondaryRules(String content) {
        if (content == null || content.isBlank()) {
            return true;
        }
        return !BLOCKED.matcher(content.toLowerCase(Locale.ROOT)).find();
    }

    private static void assertTextClean(String content) {
        if (content == null || content.isBlank()) {
            return;
        }
        if (!passesSecondaryRules(content)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "内容包含不当用语，请修改后重试");
        }
    }
}
