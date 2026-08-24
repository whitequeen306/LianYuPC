package com.lianyu.admin.release;

import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.regex.Pattern;

@Service
public class ReleaseValidationService {
    private static final Pattern SEMVER = Pattern.compile("^v?(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)(?:-[0-9A-Za-z.-]+)?(?:\\+[0-9A-Za-z.-]+)?$");
    private static final Pattern SHA512 = Pattern.compile("^[0-9a-fA-F]{128}$");
    public String normalizeVersion(String version) {
        if (version == null || !SEMVER.matcher(version.trim()).matches()) throw new IllegalArgumentException("版本号必须符合 SemVer");
        return version.trim().startsWith("v") ? version.trim().substring(1) : version.trim();
    }
    public String validateChannel(String channel) {
        String normalized = channel == null ? "stable" : channel.trim().toLowerCase(Locale.ROOT);
        if (!normalized.equals("stable") && !normalized.equals("beta")) throw new IllegalArgumentException("渠道只能是 stable 或 beta");
        return normalized;
    }
    public void validateSha512(String sha512) { if (sha512 == null || !SHA512.matcher(sha512.trim()).matches()) throw new IllegalArgumentException("SHA-512 校验值无效"); }
    public void validatePackage(String fileName, long size) {
        if (fileName == null || !fileName.matches("LianYu-Setup-[0-9A-Za-z.+-]+\\.exe")) throw new IllegalArgumentException("安装包文件名不符合规范");
        if (size <= 0 || size > 1024L * 1024L * 1024L) throw new IllegalArgumentException("安装包大小超出限制");
    }
}
