package com.lianyu.admin.identity;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.Base64;

public final class TotpUtil {
    private TotpUtil() {}
    public static String newSecret() { byte[] bytes = new byte[20]; new SecureRandom().nextBytes(bytes); return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    public static String uri(String issuer, String account, String secret) { return "otpauth://totp/" + issuer + ":" + account + "?secret=" + secret + "&issuer=" + issuer + "&algorithm=SHA1&digits=6&period=30"; }
    public static boolean verify(String secret, String code, long nowMillis) {
        if (secret == null || code == null || !code.matches("\\d{6}")) return false;
        long counter = nowMillis / 30_000L;
        for (long offset = -1; offset <= 1; offset++) if (generate(secret, counter + offset).equals(code)) return true;
        return false;
    }
    private static String generate(String secret, long counter) {
        try {
            byte[] key = Base64.getUrlDecoder().decode(secret);
            Mac mac = Mac.getInstance("HmacSHA1"); mac.init(new SecretKeySpec(key, "HmacSHA1")); byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(counter).array());
            int p = hash[hash.length - 1] & 0xf; int value = ((hash[p] & 0x7f) << 24) | ((hash[p+1] & 0xff) << 16) | ((hash[p+2] & 0xff) << 8) | (hash[p+3] & 0xff);
            return "%06d".formatted(value % 1_000_000);
        } catch (Exception e) { return ""; }
    }
}
