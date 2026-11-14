package com.lianyu.web.filter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

/**
 * {@code /internal/**} 是网关唯一的入口，Sa-Token 又管不到它，所以这层的拒绝路径必须逐个钉住。
 */
class InternalSignatureFilterTest {

    private static final String SECRET = "gw-secret";
    private static final String URI = "/internal/qq/turn";
    private static final String BODY = "{\"channel\":\"qqbot-c2c\",\"externalId\":\"U1\",\"text\":\"在吗\"}";

    @Test
    void acceptsCorrectlySignedRequest() throws Exception {
        String ts = String.valueOf(System.currentTimeMillis());
        String nonce = UUID.randomUUID().toString();
        HttpServletResponse response = response();
        FilterChain chain = mock(FilterChain.class);

        filter(SECRET).doFilter(signedRequest(ts, nonce, BODY), response, chain);

        verify(chain).doFilter(any(), any());
        verify(response, never()).setStatus(anyInt());
    }

    @Test
    void rejectsMissingHeaders() throws Exception {
        HttpServletResponse response = response();
        FilterChain chain = mock(FilterChain.class);

        filter(SECRET).doFilter(request(URI, "POST", BODY), response, chain);

        verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void rejectsTimestampOutsideWindow() throws Exception {
        // 10 分钟前的请求：即使签名算对，也要按过期拒绝，否则抓到一个包可以一直用。
        String ts = String.valueOf(System.currentTimeMillis() - 600_000L);
        String nonce = UUID.randomUUID().toString();
        HttpServletResponse response = response();
        FilterChain chain = mock(FilterChain.class);

        filter(SECRET).doFilter(signedRequest(ts, nonce, BODY), response, chain);

        verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void rejectsReplayedNonce() throws Exception {
        String ts = String.valueOf(System.currentTimeMillis());
        String nonce = "fixed-nonce";
        InternalSignatureFilter filter = filter(SECRET);
        HttpServletResponse first = response();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(signedRequest(ts, nonce, BODY), first, chain);
        HttpServletResponse second = response();
        filter.doFilter(signedRequest(ts, nonce, BODY), second, chain);

        verify(chain).doFilter(any(), any());
        verify(second).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    }

    @Test
    void rejectsBodyTamperedAfterSigning() throws Exception {
        // 只签 path 不签 body 的话，绑定码能被中间人换掉这个请求体捡走。
        String ts = String.valueOf(System.currentTimeMillis());
        String nonce = UUID.randomUUID().toString();
        HttpServletResponse response = response();
        FilterChain chain = mock(FilterChain.class);

        filter(SECRET).doFilter(signedRequest(ts, nonce, BODY, BODY.replace("在吗", "换个号")), response, chain);

        verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void failsClosedWhenSecretMissing() throws Exception {
        HttpServletResponse response = response();
        FilterChain chain = mock(FilterChain.class);

        filter("").doFilter(request(URI, "POST", BODY), response, chain);

        verify(response).setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void ignoresNonInternalPaths() throws Exception {
        HttpServletResponse response = response();
        FilterChain chain = mock(FilterChain.class);

        filter(SECRET).doFilter(request("/api/qq/bindings", "GET", ""), response, chain);

        verify(chain).doFilter(any(), any());
        verify(response, never()).setStatus(anyInt());
    }

    private static InternalSignatureFilter filter(String secret) {
        return new InternalSignatureFilter(secret);
    }

    // 拒绝时会往 body 写 JSON；mock 默认 getWriter() 是 null，必须给个真 Writer 才走得到 setStatus 之后的代码。
    private static HttpServletResponse response() throws IOException {
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(response.getWriter()).thenReturn(new PrintWriter(new StringWriter()));
        return response;
    }

    private static HttpServletRequest signedRequest(String ts, String nonce, String body) throws Exception {
        return signedRequest(ts, nonce, body, body);
    }

    /** {@code body} 是实际发送的请求体，{@code signedBody} 是签名时假定的请求体（二者不同即模拟篡改）。 */
    private static HttpServletRequest signedRequest(String ts, String nonce, String body, String signedBody) throws Exception {
        HttpServletRequest request = request(URI, "POST", body);
        when(request.getHeader("x-qq-ts")).thenReturn(ts);
        when(request.getHeader("x-qq-nonce")).thenReturn(nonce);
        when(request.getHeader("x-qq-sign")).thenReturn(sign(ts, nonce, "POST", URI, signedBody));
        return request;
    }

    private static HttpServletRequest request(String uri, String method, String body) throws IOException {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn(uri);
        when(request.getMethod()).thenReturn(method);
        when(request.getInputStream()).thenReturn(stream(body));
        return request;
    }

    private static String sign(String ts, String nonce, String method, String uri, String body) throws Exception {
        String canonical = ts + "\n" + nonce + "\n" + method + "\n" + uri + "\n" + sha256Hex(body);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return toHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
    }

    private static String sha256Hex(String body) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return toHex(digest.digest(body.getBytes(StandardCharsets.UTF_8)));
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    private static ServletInputStream stream(String body) {
        ByteArrayInputStream source = new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
        return new ServletInputStream() {
            @Override
            public boolean isFinished() {
                return source.available() == 0;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(ReadListener readListener) {
                throw new UnsupportedOperationException();
            }

            @Override
            public int read() {
                return source.read();
            }
        };
    }
}

