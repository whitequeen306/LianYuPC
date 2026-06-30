package com.lianyu.common.util;

import com.lianyu.common.base.ErrorCode;
import com.lianyu.common.exception.BusinessException;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 用户可配置的 AI Base URL 出站校验，降低 SSRF 风险。
 */
public final class OutboundUrlValidator {

    private static final Set<String> BLOCKED_HOSTS = Set.of(
            "localhost", "127.0.0.1", "0.0.0.0", "::1", "[::1]",
            "metadata.google.internal"
    );

    private OutboundUrlValidator() {
    }

    /**
     * 校验通过后固化解析结果：解析一次、固定使用，防 DNS 重绑定 SSRF。
     * 返回的 {@link ValidatedEndpoint#pinnedIps()} 为本次解析到的安全 IP，
     * 供出站 HTTP 客户端固定连接使用（保留原主机名的 SNI/Host/证书校验）。
     * Ollama 本地端点（本机 11434）单独放行且不固定 IP（受信）。
     */
    public static ValidatedEndpoint validateAndResolve(String baseUrl, boolean ollamaAllowed) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Base URL 不能为空");
        }
        String trimmed = baseUrl.trim();
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Base URL 须以 http:// 或 https:// 开头");
        }
        URI uri;
        try {
            uri = new URI(trimmed);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Base URL 格式无效");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Base URL 缺少主机名");
        }
        String lowerHost = host.toLowerCase(Locale.ROOT);
        if (BLOCKED_HOSTS.contains(lowerHost)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Base URL 不允许指向本机或元数据地址");
        }
        if (lowerHost.endsWith(".local") || lowerHost.endsWith(".internal")) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Base URL 主机名不允许使用内网域名");
        }
        int port = uri.getPort();
        if (port == -1) {
            port = "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
        }
        if (ollamaAllowed && isOllamaLocalEndpoint(trimmed, lowerHost)) {
            // 受信本地 ollama，不固定 IP
            return new ValidatedEndpoint(trimmed, lowerHost, port, List.of());
        }
        List<InetAddress> resolved;
        try {
            InetAddress[] addresses = InetAddress.getAllByName(host);
            for (InetAddress address : addresses) {
                if (isBlockedAddress(address)) {
                    throw new BusinessException(ErrorCode.BAD_REQUEST,
                            "Base URL 解析到内网或保留地址，不允许访问");
                }
            }
            resolved = List.copyOf(Arrays.asList(addresses));
        } catch (UnknownHostException e) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Base URL 主机名无法解析");
        }
        return new ValidatedEndpoint(trimmed, lowerHost, port, resolved);
    }

    /**
     * 仅校验并归一化 Base URL（不返回解析结果）。
     * 保留供 vault 保存时轻量校验；调用时若需固定 IP 请用 {@link #validateAndResolve}。
     */
    public static String validateAndNormalize(String baseUrl, boolean ollamaAllowed) {
        return validateAndResolve(baseUrl, ollamaAllowed).url();
    }

    /**
     * 已校验的出站端点：URL、主机名、端口、解析到的安全 IP（空表示受信本地端点不固定）。
     */
    public record ValidatedEndpoint(String url, String host, int port, List<InetAddress> pinnedIps) {
        public ValidatedEndpoint {
            pinnedIps = pinnedIps == null ? List.of() : List.copyOf(pinnedIps);
        }

        public boolean isPinningRequired() {
            return !pinnedIps.isEmpty();
        }
    }

    public static boolean isOllamaLocalEndpoint(String baseUrl, String lowerHost) {
        if (baseUrl == null) {
            return false;
        }
        String lower = baseUrl.toLowerCase(Locale.ROOT);
        return (lower.contains(":11434") || lower.contains("ollama"))
                && ("localhost".equals(lowerHost) || "127.0.0.1".equals(lowerHost));
    }

    private static boolean isBlockedAddress(InetAddress address) {
        if (address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int b0 = Byte.toUnsignedInt(bytes[0]);
            int b1 = Byte.toUnsignedInt(bytes[1]);
            // 169.254.0.0/16 link-local / cloud metadata
            if (b0 == 169 && b1 == 254) {
                return true;
            }
            // 0.0.0.0/8
            if (b0 == 0) {
                return true;
            }
        }
        return false;
    }
}
