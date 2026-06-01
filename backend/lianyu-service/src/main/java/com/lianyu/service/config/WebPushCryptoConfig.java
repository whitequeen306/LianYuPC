package com.lianyu.service.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.springframework.context.annotation.Configuration;

import java.security.Security;

/**
 * web-push 库依赖 JCA Provider 名称为 {@code BC}（Bouncy Castle），需在首次推送前注册。
 */
@Slf4j
@Configuration
public class WebPushCryptoConfig {

    @PostConstruct
    void registerBouncyCastle() {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) != null) {
            return;
        }
        Security.addProvider(new BouncyCastleProvider());
        log.info("Bouncy Castle security provider registered for Web Push");
    }
}
