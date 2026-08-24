package com.lianyu.admin.release;

import com.lianyu.ai.SsrfPinningClientFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.Map;

@Service
@RequiredArgsConstructor
public class GitHubReleaseImporter {
    private final ReleaseValidationService validation;
    public Map<String,Object> inspect(String apiUrl, String repository, String tag) {
        if (apiUrl == null || !apiUrl.equals("https://api.github.com")) throw new IllegalArgumentException("GitHub API 地址不在允许列表");
        if (repository == null || !repository.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")) throw new IllegalArgumentException("仓库格式无效");
        if (tag == null || !tag.matches("v?[0-9]+\\.[0-9]+\\.[0-9]+.*")) throw new IllegalArgumentException("Tag 不是 SemVer");
        RestClient client = SsrfPinningClientFactory.defaultRestClientBuilder().baseUrl(apiUrl).build();
        Map<?,?> release = client.get().uri("/repos/{repo}/releases/tags/{tag}", repository, tag).accept(MediaType.APPLICATION_JSON).retrieve().body(Map.class);
        Object assets = release.containsKey("assets") ? release.get("assets") : java.util.List.of();
        return Map.of("tag", tag, "name", String.valueOf(release.get("name")), "htmlUrl", String.valueOf(release.get("html_url")), "assets", assets);
    }
}
