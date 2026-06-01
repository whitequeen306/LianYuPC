package com.lianyu.security.util;

import lombok.extern.slf4j.Slf4j;
import org.jasypt.encryption.StringEncryptor;
import org.jasypt.encryption.pbe.PooledPBEStringEncryptor;
import org.jasypt.encryption.pbe.config.SimpleStringPBEConfig;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class JasyptUtil {

    private static final String CURRENT = "current";
    private final Map<String, StringEncryptor> encryptors = new ConcurrentHashMap<>();
    private String currentVersion;

    public JasyptUtil() {
        String masterKey = System.getenv("LIANYU_MASTER_KEY");
        if (masterKey == null || masterKey.isBlank()) {
            log.warn("LIANYU_MASTER_KEY not set — Jasypt encryption disabled. Set env var before production use.");
            return;
        }
        parseMasterKeys(masterKey);
    }

    private void parseMasterKeys(String raw) {
        // Format: v1=base64key1,v2=base64key2,current=v2
        String[] parts = raw.split(",");
        for (String part : parts) {
            String[] kv = part.split("=", 2);
            if (kv.length != 2) continue;
            String key = kv[0].trim();
            String val = kv[1].trim();
            if (CURRENT.equals(key)) {
                currentVersion = val;
            } else {
                encryptors.put(key, createEncryptor(val));
            }
        }
        log.info("Jasypt initialized with {} key versions, current={}", encryptors.size(), currentVersion);
    }

    private StringEncryptor createEncryptor(String base64Key) {
        PooledPBEStringEncryptor encryptor = new PooledPBEStringEncryptor();
        SimpleStringPBEConfig config = new SimpleStringPBEConfig();
        config.setPassword(base64Key);
        config.setAlgorithm("PBEWITHHMACSHA512ANDAES_256");
        config.setPoolSize(4);
        config.setKeyObtentionIterations("1000");
        config.setProviderName("SunJCE");
        config.setSaltGeneratorClassName("org.jasypt.salt.RandomSaltGenerator");
        config.setIvGeneratorClassName("org.jasypt.iv.RandomIvGenerator");
        config.setStringOutputType("base64");
        encryptor.setConfig(config);
        return encryptor;
    }

    public String encrypt(String plaintext) {
        if (plaintext == null) return null;
        if (currentVersion == null || encryptors.isEmpty()) {
            log.warn("No Jasypt encryptor available, returning plaintext");
            return plaintext;
        }
        StringEncryptor e = encryptors.get(currentVersion);
        if (e == null) {
            log.warn("No Jasypt encryptor available, returning plaintext");
            return plaintext;
        }
        return e.encrypt(plaintext);
    }

    public String decrypt(String ciphertext) {
        if (ciphertext == null) return null;
        // If no encryptors configured, return as-is (encryption disabled)
        if (encryptors.isEmpty()) {
            return ciphertext;
        }
        // Try current version first, fall back to all known versions
        if (currentVersion != null) {
            StringEncryptor current = encryptors.get(currentVersion);
            if (current != null) {
                try { return current.decrypt(ciphertext); } catch (Exception ignored) {}
            }
        }
        for (Map.Entry<String, StringEncryptor> entry : encryptors.entrySet()) {
            try { return entry.getValue().decrypt(ciphertext); } catch (Exception ignored) {}
        }
        log.warn("Failed to decrypt with any known key version");
        throw new IllegalStateException(
                "Failed to decrypt with LIANYU_MASTER_KEY; ciphertext may use a different master key version");
    }

    /** True when value looks like a usable API key (not a Jasypt ciphertext blob). */
    public static boolean looksLikeApiKey(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String trimmed = value.trim();
        if (trimmed.length() > 200) {
            return false;
        }
        return trimmed.startsWith("sk-") || "local".equals(trimmed);
    }

    public String getCurrentVersion() {
        return currentVersion != null ? currentVersion : "plaintext";
    }
}
