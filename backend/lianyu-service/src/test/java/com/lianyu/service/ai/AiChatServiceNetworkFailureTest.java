package com.lianyu.service.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.UnknownHostException;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;

class AiChatServiceNetworkFailureTest {

    @Test
    void classifiesWrappedDnsFailureAsTransient() {
        Throwable error = new CompletionException(
                new IllegalStateException("request failed", new UnknownHostException("api.deepseek.com")));

        assertThat(AiChatService.isTransientStreamFailure(error)).isTrue();
    }

    @Test
    void doesNotClassifyAuthenticationFailureAsTransient() {
        assertThat(AiChatService.isTransientStreamFailure(
                new IllegalArgumentException("401 unauthorized"))).isFalse();
    }
}
