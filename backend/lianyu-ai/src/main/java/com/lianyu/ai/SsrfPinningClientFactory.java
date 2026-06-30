package com.lianyu.ai;

import com.lianyu.common.util.OutboundUrlValidator.ValidatedEndpoint;
import io.netty.resolver.AddressResolver;
import io.netty.resolver.AddressResolverGroup;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.Promise;
import okhttp3.Dns;
import okhttp3.OkHttpClient;
import org.springframework.http.client.OkHttp3ClientHttpRequestFactory;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

/**
 * 构建 SSRF 防护的 HTTP 客户端：将连接固定到 {@link ValidatedEndpoint#pinnedIps()} 解析到的 IP，
 * 避免 DNS 重绑定在请求时绕过 {@link com.lianyu.common.util.OutboundUrlValidator} 的内网封锁。
 *
 * <p>仅对远程端点启用固定；localhost/ollama-local 不固定（受信）。固定失败即闭——构造抛异常时
 * 请求失败，不会静默回退到不固定。出站 DNS「只放行校验过的 IP」：非目标主机一律拒绝解析，
 * 兼防重定向型 SSRF。
 *
 * <p>两条传输路径：
 * <ul>
 *   <li>阻塞路径（RestClient/.call）：OkHttp 自定义 {@link Dns} 固定目标主机 IP，
 *       TLS 的 SNI/Host/证书校验仍用 URL 主机名（OkHttp 行为）。</li>
 *   <li>流式路径（WebClient/.stream）：reactor-netty 自定义 {@link AddressResolverGroup}
 *       固定目标主机 IP。SNI/Host/证书校验在 {@code onChannelInit} 阶段用未解析地址的主机名，
 *       连接地址用解析后的固定 IP——两者解耦，TLS 不受固定影响。</li>
 * </ul>
 */
public final class SsrfPinningClientFactory {

    private SsrfPinningClientFactory() {
    }

    /**
     * 阻塞调用路径（RestClient/.call）：通过 OkHttp 自定义 Dns 把目标主机固定为已校验 IP。
     * 仅当端点需要固定时装配；否则返回默认 Builder（受信本地端点）。
     */
    public static RestClient.Builder restClientBuilder(ValidatedEndpoint endpoint) {
        if (!endpoint.isPinningRequired()) {
            return RestClient.builder();
        }
        OkHttpClient okHttp = new OkHttpClient.Builder()
                .dns(new PinnedDns(endpoint.host(), endpoint.pinnedIps()))
                .build();
        return RestClient.builder().requestFactory(new OkHttp3ClientHttpRequestFactory(okHttp));
    }

    /**
     * 流式调用路径（WebClient/.stream）：通过 reactor-netty 自定义 AddressResolverGroup
     * 把目标主机解析固定为已校验 IP。仅当端点需要固定时装配；否则返回默认 Builder（受信本地端点）。
     */
    public static WebClient.Builder webClientBuilder(ValidatedEndpoint endpoint) {
        if (!endpoint.isPinningRequired()) {
            return WebClient.builder();
        }
        HttpClient httpClient = HttpClient.create()
                .resolver(new PinnedAddressResolverGroup(endpoint.host(), endpoint.port(), endpoint.pinnedIps()))
                .secure();
        return WebClient.builder().clientConnector(new ReactorClientHttpConnector(httpClient));
    }

    /**
     * OkHttp Dns：仅对目标主机返回已校验 IP，其余主机一律拒绝（fail-closed，防重定向 SSRF）。
     * OkHttp 只对请求主机名调用 lookup，故拒绝非目标主机是安全的。
     */
    static final class PinnedDns implements Dns {
        private final String pinnedHost;
        private final List<InetAddress> pinnedIps;

        PinnedDns(String pinnedHost, List<InetAddress> pinnedIps) {
            this.pinnedHost = pinnedHost;
            this.pinnedIps = pinnedIps;
        }

        @Override
        public List<InetAddress> lookup(String hostname) throws UnknownHostException {
            if (pinnedHost.equalsIgnoreCase(hostname)) {
                return pinnedIps;
            }
            throw new UnknownHostException("SSRF pinning: host not permitted: " + hostname);
        }
    }

    /**
     * netty AddressResolverGroup：仅对目标主机的未解析地址返回固定 IP，其余地址一律失败（fail-closed）。
     * reactor-netty 通过 {@link AddressResolver#resolveAll} 取连接地址列表，故实现 resolveAll。
     */
    static final class PinnedAddressResolverGroup extends AddressResolverGroup<InetSocketAddress> {
        private final String pinnedHost;
        private final int port;
        private final List<InetAddress> pinnedIps;

        PinnedAddressResolverGroup(String pinnedHost, int port, List<InetAddress> pinnedIps) {
            this.pinnedHost = pinnedHost;
            this.port = port;
            this.pinnedIps = pinnedIps;
        }

        @Override
        protected AddressResolver<InetSocketAddress> newResolver(EventExecutor executor) {
            return new PinnedAddressResolver(executor, pinnedHost, port, pinnedIps);
        }
    }

    static final class PinnedAddressResolver implements AddressResolver<InetSocketAddress> {
        private final EventExecutor executor;
        private final String pinnedHost;
        private final int port;
        private final List<InetAddress> pinnedIps;

        PinnedAddressResolver(EventExecutor executor, String pinnedHost, int port, List<InetAddress> pinnedIps) {
            this.executor = executor;
            this.pinnedHost = pinnedHost;
            this.port = port;
            this.pinnedIps = pinnedIps;
        }

        @Override
        public boolean isSupported(SocketAddress address) {
            return address instanceof InetSocketAddress;
        }

        @Override
        public boolean isResolved(SocketAddress address) {
            return address instanceof InetSocketAddress && !((InetSocketAddress) address).isUnresolved();
        }

        @Override
        public Future<InetSocketAddress> resolve(SocketAddress address) {
            return resolve(address, executor.newPromise());
        }

        @Override
        public Future<InetSocketAddress> resolve(SocketAddress address, Promise<InetSocketAddress> promise) {
            List<InetSocketAddress> all = pinAll(address);
            if (all != null) {
                promise.trySuccess(all.get(0));
                return promise;
            }
            promise.tryFailure(new UnknownHostException("SSRF pinning: host not permitted: " + hostOf(address)));
            return promise;
        }

        @Override
        public Future<List<InetSocketAddress>> resolveAll(SocketAddress address) {
            return resolveAll(address, executor.<List<InetSocketAddress>>newPromise());
        }

        @Override
        public Future<List<InetSocketAddress>> resolveAll(SocketAddress address, Promise<List<InetSocketAddress>> promise) {
            List<InetSocketAddress> all = pinAll(address);
            if (all != null) {
                promise.trySuccess(all);
                return promise;
            }
            promise.tryFailure(new UnknownHostException("SSRF pinning: host not permitted: " + hostOf(address)));
            return promise;
        }

        @Override
        public void close() {
            // 无需释放资源
        }

        private List<InetSocketAddress> pinAll(SocketAddress address) {
            if (!(address instanceof InetSocketAddress)) {
                return null;
            }
            InetSocketAddress addr = (InetSocketAddress) address;
            if (!pinnedHost.equalsIgnoreCase(addr.getHostString())) {
                return null;
            }
            List<InetSocketAddress> out = new ArrayList<>(pinnedIps.size());
            for (InetAddress ip : pinnedIps) {
                out.add(new InetSocketAddress(ip, port));
            }
            return out;
        }

        private static String hostOf(SocketAddress address) {
            if (address instanceof InetSocketAddress) {
                return ((InetSocketAddress) address).getHostString();
            }
            return String.valueOf(address);
        }
    }
}
