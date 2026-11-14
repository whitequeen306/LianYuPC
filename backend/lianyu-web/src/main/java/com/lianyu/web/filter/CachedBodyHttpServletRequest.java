package com.lianyu.web.filter;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * 把请求体一次读进内存，好让签名过滤器算完摘要之后，后面还能再读一遍。
 * <p>{@code ContentCachingRequestWrapper} 做不到这点：它只缓存下游读过的内容，
 * 过滤器先读就会把下游的流读空。
 */
public class CachedBodyHttpServletRequest extends HttpServletRequestWrapper {

    private final byte[] body;

    public CachedBodyHttpServletRequest(HttpServletRequest request) throws IOException {
        super(request);
        this.body = request.getInputStream().readAllBytes();
    }

    public byte[] getBody() {
        return body;
    }

    @Override
    public ServletInputStream getInputStream() {
        ByteArrayInputStream source = new ByteArrayInputStream(body);
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
                throw new UnsupportedOperationException("async read is not supported on cached body");
            }

            @Override
            public int read() {
                return source.read();
            }

            @Override
            public int read(byte[] target, int offset, int length) {
                return source.read(target, offset, length);
            }
        };
    }

    @Override
    public BufferedReader getReader() {
        Charset charset = StandardCharsets.UTF_8;
        String encoding = getCharacterEncoding();
        if (encoding != null && !encoding.isBlank()) {
            charset = Charset.forName(encoding);
        }
        return new BufferedReader(new InputStreamReader(getInputStream(), charset));
    }
}
