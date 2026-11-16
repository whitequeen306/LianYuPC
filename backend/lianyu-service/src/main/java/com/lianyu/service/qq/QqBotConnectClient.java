package com.lianyu.service.qq;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;

/**
 * 腾讯「扫码连接 QQ 机器人」协议的唯一出口。
 *
 * <p>协议来自官方 SDK {@code @tencent-connect/qqbot-connector}：先在
 * {@code POST /lite/create_bind_task} 用一把本地生成的 AES-256 钥匙换 taskId，
 * 用户在手机 QQ 里扫码确认后，{@code POST /lite/poll_bind_result} 会返回
 * {@code bot_appid} 与用该钥匙加密的 {@code bot_encrypt_secret}（AES-256-GCM，
 * 结构是 IV(12) + ciphertext + tag(16)）。
 *
 * <p>为什么不直接在网关里用那个 npm 包：网关在公网、只该搬运消息；凭据一旦
 * 落在它手上就等于把所有用户的 bot 密码摊在暴露面上。这里由 Java 拿凭据、
 * 加密入库，网关只向 {@code /internal/qq/token} 要短期 accessToken。
 */
@Slf4j
@Component
public class QqBotConnectClient {

    private static final String BIND_HOST = "https://q.qq.com";
    private static final String TOKEN_URL = "https://api.bot.qq.com/app/getAppAccessToken";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public QqBotConnectClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /** 新扫码任务：taskId 用于拼二维码 URL，key 用于之后解密钥（只留在服务端内存）。 */
    public record BindTask(String taskId, String keyBase64) {
    }

    /** 轮询结果；status 0=NONE 1=PENDING 2=COMPLETED 3=EXPIRED。 */
    public record BindPoll(int status, String appId, String encryptSecret, String openId) {
        public boolean completed() {
            return status == 2 && appId != null && !appId.isBlank();
        }

        public boolean expired() {
            return status == 3;
        }
    }

    public record AccessToken(String accessToken, long expiresInSeconds) {
    }

    public BindTask createBindTask() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        String keyBase64 = Base64.getEncoder().encodeToString(key);
        JsonNode root = postJson(BIND_HOST + "/lite/create_bind_task", Map.of("key", keyBase64));
        int retcode = root.path("retcode").asInt(-1);
        String taskId = root.path("data").path("task_id").asText("");
        if (retcode != 0 || taskId.isBlank()) {
            throw new IllegalStateException("create_bind_task failed: retcode=" + retcode
                    + " msg=" + root.path("msg").asText(""));
        }
        return new BindTask(taskId, keyBase64);
    }

    public BindPoll pollBindResult(String taskId) {
        JsonNode root = postJson(BIND_HOST + "/lite/poll_bind_result", Map.of("task_id", taskId));
        int retcode = root.path("retcode").asInt(-1);
        if (retcode != 0) {
            throw new IllegalStateException("poll_bind_result failed: retcode=" + retcode
                    + " msg=" + root.path("msg").asText(""));
        }
        JsonNode data = root.path("data");
        return new BindPoll(
                data.path("status").asInt(0),
                textOrNull(data.path("bot_appid").asText("")),
                textOrNull(data.path("bot_encrypt_secret").asText("")),
                textOrNull(data.path("user_openid").asText("")));
    }

    /** 二维码里编码的地址：用户用手机 QQ 扫它进入「连接到QQ Bot」页。 */
    public String connectUrl(String taskId, String source) {
        return BIND_HOST + "/qqbot/openclaw/connect.html?task_id="
                + java.net.URLEncoder.encode(taskId, StandardCharsets.UTF_8)
                + "&source=" + java.net.URLEncoder.encode(source == null ? "" : source, StandardCharsets.UTF_8)
                + "&_wv=2";
    }

    /** AES-256-GCM 解密：IV 在前 12 字节，tag 在末 16 字节。 */
    public String decryptSecret(String encryptSecretBase64, String keyBase64) {
        try {
            byte[] key = Base64.getDecoder().decode(keyBase64);
            byte[] raw = Base64.getDecoder().decode(encryptSecretBase64);
            if (raw.length <= 28) {
                throw new IllegalStateException("encrypted secret too short");
            }
            byte[] iv = Arrays.copyOfRange(raw, 0, 12);
            byte[] tag = Arrays.copyOfRange(raw, raw.length - 16, raw.length);
            byte[] cipherText = Arrays.copyOfRange(raw, 12, raw.length - 16);
            byte[] input = new byte[cipherText.length + tag.length];
            System.arraycopy(cipherText, 0, input, 0, cipherText.length);
            System.arraycopy(tag, 0, input, cipherText.length, tag.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(input), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("decrypt bot secret failed: " + e.getMessage(), e);
        }
    }

    /**
     * 用凭据换 accessToken（7200s）。注意腾讯这把接口**失败也返回 HTTP 200**，
     * 必须看 body 的 code 字段，不能按状态码判定。
     */
    public AccessToken fetchAccessToken(String appId, String appSecret) {
        JsonNode root = postJson(TOKEN_URL, Map.of("appId", appId, "clientSecret", appSecret));
        String token = root.path("access_token").asText("");
        if (token.isBlank()) {
            throw new IllegalStateException("getAppAccessToken failed: code=" + root.path("code").asText("")
                    + " message=" + root.path("message").asText(""));
        }
        return new AccessToken(token, root.path("expires_in").asLong(7200));
    }

    private JsonNode postJson(String url, Map<String, Object> body) {
        try {
            String payload = objectMapper.writeValueAsString(body);
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("HTTP " + response.statusCode() + " from " + url);
            }
            return objectMapper.readTree(response.body());
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("request " + url + " failed: " + e.getMessage(), e);
        }
    }

    private static String textOrNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
