package com.lianyu.web.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

class ClientIpResolverTest {

    @Test
    void resolve_usesForwardedForWhenTrusted() {
        ClientIpResolver resolver = new ClientIpResolver(true);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("X-Forwarded-For")).thenReturn("203.0.113.1, 10.0.0.1");
        assertEquals("203.0.113.1", resolver.resolve(request));
    }

    @Test
    void resolve_usesRemoteAddrWhenNotTrusted() {
        ClientIpResolver resolver = new ClientIpResolver(false);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("X-Forwarded-For")).thenReturn("203.0.113.1");
        when(request.getRemoteAddr()).thenReturn("10.0.0.5");
        assertEquals("10.0.0.5", resolver.resolve(request));
    }
}
