package com.lianyu.web.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * 客户端自动更新代理 — 代替客户端直接访问 GitHub Releases（私有仓库）。
 *
 * 客户端 electron-updater 使用 generic provider，指向 /api/public/updater。
 * 本控制器用服务端持有的 GitHub token 从私有仓库拉取 latest.yml 和安装包，
 * 将 latest.yml 中的下载链接改写为后端代理地址后返回给客户端。
 *
 * latest.yml 流程：
 *   1. 调 GitHub API /releases/latest 获取最新 release
 *   2. 找到 latest.yml 资产，下载其内容
 *   3. 把 url 字段改写为 /api/public/updater/download?asset=xxx
 *   4. 返回改写后的 YAML
 *
 * download 流程：
 *   1. 从 latest.yml 缓存的资产 URL 直接流式代理
 *   2. 逐块写入客户端，不落地磁盘
 */
@Slf4j
@Tag(name = "Updater", description = "客户端自动更新代理（GitHub Releases 私有仓库）")
@RestController
@RequiredArgsConstructor
public class UpdaterController {

    private final ObjectMapper objectMapper;

    @Value("${lianyu.updater.github-token:}")
    private String githubToken;

    @Value("${lianyu.updater.owner:whitequeen306}")
    private String owner;

    @Value("${lianyu.updater.repo:LianYuPC}")
    private String repo;

    private static final String GITHUB_API_BASE = "https://api.github.com/repos";

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .build();

    @Operation(summary = "获取 latest.yml（改写下载链接为后端代理）")
    @GetMapping("/api/public/updater/latest.yml")
    public ResponseEntity<String> getLatestYml() {
        if (githubToken == null || githubToken.isBlank()) {
            log.warn("updater: github-token not configured");
            return ResponseEntity.internalServerError().body("updater not configured");
        }
        try {
            // 1. 获取最新 release 信息
            String apiUrl = GITHUB_API_BASE + "/" + owner + "/" + repo + "/releases/latest";
            HttpRequest apiReq = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .header("Authorization", "token " + githubToken)
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "lianyu-updater")
                    .timeout(Duration.ofSeconds(15))
                    .GET()
                    .build();
            HttpResponse<String> apiResp = HTTP.send(apiReq, HttpResponse.BodyHandlers.ofString());
            if (apiResp.statusCode() != 200) {
                log.warn("updater: GitHub API returned {}", apiResp.statusCode());
                return ResponseEntity.status(apiResp.statusCode()).body("GitHub API error");
            }

            JsonNode release = objectMapper.readTree(apiResp.body());
            String tag = release.path("tag_name").asText("");

            // 2. 找到 latest.yml 资产的下载 URL
            String latestYmlUrl = null;
            for (JsonNode asset : release.path("assets")) {
                if ("latest.yml".equals(asset.path("name").asText())) {
                    latestYmlUrl = asset.path("url").asText();  // API URL (需 token)
                    break;
                }
            }
            if (latestYmlUrl == null) {
                log.warn("updater: latest.yml not found in release {}", tag);
                return ResponseEntity.notFound().build();
            }

            // 3. 下载 latest.yml 内容
            HttpRequest ymlReq = HttpRequest.newBuilder()
                    .uri(URI.create(latestYmlUrl))
                    .header("Authorization", "token " + githubToken)
                    .header("Accept", "application/octet-stream")
                    .header("User-Agent", "lianyu-updater")
                    .timeout(Duration.ofSeconds(15))
                    .GET()
                    .build();
            HttpResponse<String> ymlResp = HTTP.send(ymlReq, HttpResponse.BodyHandlers.ofString());
            if (ymlResp.statusCode() != 200) {
                log.warn("updater: latest.yml download returned {}", ymlResp.statusCode());
                return ResponseEntity.status(ymlResp.statusCode()).body("latest.yml download error");
            }

            // 4. 改写 url 字段：把 GitHub 资产名改写为后端代理地址
            String yml = ymlResp.body();
            // 匹配 "url: xxxx" 行，把值替换为后端代理地址
            // latest.yml 格式：  - url: LianYu.Setup.0.2.256.exe
            // 改写为：           - url: /api/public/updater/download?asset=LianYu.Setup.0.2.256.exe
            yml = yml.replaceAll(
                    "(?m)(^(?:\\s*-?\\s*)url:\\s*)(\\S+)(\\s*$)",
                    "$1/api/public/updater/download?asset=$2$3"
            );
            // path: 行同理改写
            yml = yml.replaceAll(
                    "(?m)(^path:\\s*)(\\S+)(\\s*$)",
                    "$1/api/public/updater/download?asset=$2$3"
            );

            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_TYPE, "text/yaml; charset=utf-8")
                    .header(HttpHeaders.CACHE_CONTROL, "no-cache")
                    .body(yml);
        } catch (Exception e) {
            log.error("updater: failed to proxy latest.yml", e);
            return ResponseEntity.internalServerError().body("updater error");
        }
    }

    @Operation(summary = "代理下载安装包（流式转发 GitHub Release 资产）")
    @GetMapping("/api/public/updater/download")
    public ResponseEntity<StreamingResponseBody> downloadAsset(String asset) {
        if (githubToken == null || githubToken.isBlank()) {
            return ResponseEntity.internalServerError().build();
        }
        if (asset == null || asset.isBlank() || !asset.endsWith(".exe")) {
            log.warn("updater: rejected invalid asset name: {}", asset);
            return ResponseEntity.badRequest().build();
        }

        try {
            // 1. 获取最新 release，找到对应资产的 API url（非 browser_download_url）
            //    browser_download_url 是 github.com web URL，Java HttpClient 对私有仓库
            //    的 302 重定向处理有问题（Authorization 被跨域剥离后可能 403）；
            //    asset url 是 api.github.com 端点，配合 Accept: octet-stream 直接返回
            //    二进制流（与 getLatestYml 相同方式，已验证可行）。
            String apiUrl = GITHUB_API_BASE + "/" + owner + "/" + repo + "/releases/latest";
            HttpRequest apiReq = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .header("Authorization", "token " + githubToken)
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "lianyu-updater")
                    .timeout(Duration.ofSeconds(15))
                    .GET()
                    .build();
            HttpResponse<String> apiResp = HTTP.send(apiReq, HttpResponse.BodyHandlers.ofString());
            if (apiResp.statusCode() != 200) {
                return ResponseEntity.status(apiResp.statusCode()).build();
            }

            JsonNode release = objectMapper.readTree(apiResp.body());
            String assetApiUrl = null;
            long size = 0;
            for (JsonNode a : release.path("assets")) {
                if (asset.equals(a.path("name").asText())) {
                    assetApiUrl = a.path("url").asText();
                    size = a.path("size").asLong();
                    break;
                }
            }
            if (assetApiUrl == null) {
                log.warn("updater: asset {} not found in latest release", asset);
                return ResponseEntity.notFound().build();
            }

            // 2. 先发起 GitHub 下载请求并验证状态码（在 StreamingResponseBody 之外）
            //    这样失败时能返回正确的 HTTP 错误码，而不是 200 + 空 body
            HttpRequest dlReq = HttpRequest.newBuilder()
                    .uri(URI.create(assetApiUrl))
                    .header("Authorization", "token " + githubToken)
                    .header("Accept", "application/octet-stream")
                    .header("User-Agent", "lianyu-updater")
                    .timeout(Duration.ofMinutes(10))
                    .GET()
                    .build();
            HttpResponse<InputStream> dlResp = HTTP.send(dlReq, HttpResponse.BodyHandlers.ofInputStream());
            if (dlResp.statusCode() != 200) {
                log.warn("updater: asset download returned {}", dlResp.statusCode());
                return ResponseEntity.status(dlResp.statusCode()).build();
            }

            // 3. 流式转发 InputStream → 客户端（不落地磁盘）
            final InputStream in = dlResp.body();
            StreamingResponseBody body = outputStream -> {
                try (in) {
                    in.transferTo(outputStream);
                } catch (Exception e) {
                    log.error("updater: asset stream error", e);
                    throw new IOException("asset stream error", e);
                }
            };

            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_TYPE, "application/octet-stream")
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + asset + "\"")
                    .header("X-Content-Type-Options", "nosniff")
                    .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(size))
                    .body(body);
        } catch (Exception e) {
            log.error("updater: failed to proxy download", e);
            return ResponseEntity.internalServerError().build();
        }
    }
}
