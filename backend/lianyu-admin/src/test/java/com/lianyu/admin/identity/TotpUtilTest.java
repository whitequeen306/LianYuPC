package com.lianyu.admin.identity;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TotpUtilTest {
    @Test void generatedSecretProducesSixDigitCodeContract() {
        String secret = TotpUtil.newSecret();
        assertTrue(secret.length() >= 20);
        assertFalse(TotpUtil.verify(secret, "000000", System.currentTimeMillis()));
    }
}
