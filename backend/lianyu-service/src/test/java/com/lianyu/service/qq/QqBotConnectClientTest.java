package com.lianyu.service.qq;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 腾讯扫码协议里的密钥解密。这套报文格式（IV(12) + ciphertext + tag(16)，AES-256-GCM）
 * 来自官方 SDK，写错一个字节就会在真实扫码时抛异常，必须锁住。
 */
class QqBotConnectClientTest {

    private static final String SECRET = "L0gM3kSAtcM7seQD0ocRH7yphZSLF940";

    private final QqBotConnectClient client = new QqBotConnectClient(new ObjectMapper());

    /** 按腾讯的格式自己加密一份（IV 在前、tag 在后），再让被测方法解回来。 */
    private static String encryptLikeTencent(String plain, byte[] key) throws Exception {
        byte[] iv = new byte[12];
        for (int i = 0; i < iv.length; i++) {
            iv[i] = (byte) (i + 1);
        }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
        byte[] sealed = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
        byte[] tag = Arrays.copyOfRange(sealed, sealed.length - 16, sealed.length);
        byte[] cipherText = Arrays.copyOfRange(sealed, 0, sealed.length - 16);
        byte[] out = new byte[12 + cipherText.length + 16];
        System.arraycopy(iv, 0, out, 0, 12);
        System.arraycopy(cipherText, 0, out, 12, cipherText.length);
        System.arraycopy(tag, 0, out, 12 + cipherText.length, 16);
        return Base64.getEncoder().encodeToString(out);
    }

    @Test
    void decryptsSecretProducedByTencentFormat() throws Exception {
        byte[] key = new byte[32];
        for (int i = 0; i < key.length; i++) {
            key[i] = (byte) (i * 7);
        }
        String keyBase64 = Base64.getEncoder().encodeToString(key);
        String encrypted = encryptLikeTencent(SECRET, key);

        assertThat(client.decryptSecret(encrypted, keyBase64)).isEqualTo(SECRET);
    }

    @Test
    void rejectsTamperedPayload() throws Exception {
        byte[] key = new byte[32];
        String keyBase64 = Base64.getEncoder().encodeToString(key);
        String encrypted = encryptLikeTencent(SECRET, key);
        byte[] raw = Base64.getDecoder().decode(encrypted);
        raw[20] = (byte) (raw[20] ^ 0xFF);

        assertThatThrownBy(() -> client.decryptSecret(Base64.getEncoder().encodeToString(raw), keyBase64))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("decrypt bot secret failed");
    }

    @Test
    void rejectsPayloadTooShortToHoldIvAndTag() {
        assertThatThrownBy(() -> client.decryptSecret(Base64.getEncoder().encodeToString(new byte[20]), "AAAA"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("too short");
    }

    @Test
    void buildsConnectUrlWithTaskAndSource() {
        String url = client.connectUrl("task-123", "yuni-pc");

        assertThat(url)
                .startsWith("https://q.qq.com/qqbot/openclaw/connect.html?task_id=task-123")
                .contains("source=yuni-pc")
                .endsWith("&_wv=2");
    }
}
