package com.lianyu.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lianyu.common.exception.BusinessException;
import com.lianyu.common.util.OutboundUrlValidator;
import com.lianyu.common.util.OutboundUrlValidator.ValidatedEndpoint;
import io.netty.resolver.AddressResolver;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.ImmediateEventExecutor;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * I-19 (#8) DNS-rebinding SSRF 防护回归：固定已解析 IP，非目标主机 fail-closed。
 * 全程无网络：IP 用字面量构造，校验拒绝路径在 DNS 之前短路，resolver 用 ImmediateEventExecutor。
 */
class SsrfPinningClientFactoryTest {

    private static InetAddress ip(int a, int b, int c, int d) {
        try {
            return InetAddress.getByAddress(new byte[]{(byte) a, (byte) b, (byte) c, (byte) d});
        } catch (UnknownHostException e) {
            throw new AssertionError(e);
        }
    }

    // ---- OutboundUrlValidator：拒绝内网/保留/元数据地址（均在 DNS 解析之前短路，无网络） ----

    @Test
    void validateAndResolve_rejectsBlockedHostsBeforeDns() {
        assertThatThrownBy(() -> OutboundUrlValidator.validateAndResolve(null, false))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> OutboundUrlValidator.validateAndResolve("   ", false))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> OutboundUrlValidator.validateAndResolve("ftp://example.com", false))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> OutboundUrlValidator.validateAndResolve("http://localhost/x", false))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> OutboundUrlValidator.validateAndResolve("http://127.0.0.1/x", false))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> OutboundUrlValidator.validateAndResolve("http://0.0.0.0/x", false))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> OutboundUrlValidator.validateAndResolve("http://metadata.google.internal/x", false))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> OutboundUrlValidator.validateAndResolve("http://foo.local/x", false))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> OutboundUrlValidator.validateAndResolve("http://foo.internal/x", false))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> OutboundUrlValidator.validateAndResolve("http:///path", false))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void validateAndResolve_rejectsCloudMetadataIp() {
        // 169.254.169.254 是字面量 IP，getAllByName 不走 DNS；isBlockedAddress 命中 169.254/16
        assertThatThrownBy(() -> OutboundUrlValidator.validateAndResolve("http://169.254.169.254/", false))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void validateAndResolve_rejectsLocalhostOllamaEvenWhenAllowed() {
        // localhost 在 BLOCKED_HOSTS，先于 ollama 放行分支命中——本地 ollama 走配置属性而非 vault
        assertThatThrownBy(() -> OutboundUrlValidator.validateAndResolve("http://localhost:11434", true))
                .isInstanceOf(BusinessException.class);
    }

    // ---- PinnedDns（OkHttp 阻塞路径）：目标主机固定 IP，非目标主机 fail-closed ----

    @Test
    void pinnedDns_returnsPinnedIpsForTargetHostCaseInsensitive() throws Exception {
        List<InetAddress> pinned = List.of(ip(1, 2, 3, 4), ip(5, 6, 7, 8));
        SsrfPinningClientFactory.PinnedDns dns =
                new SsrfPinningClientFactory.PinnedDns("api.openai.com", pinned);

        assertThat(dns.lookup("api.openai.com")).containsExactlyElementsOf(pinned);
        assertThat(dns.lookup("API.OPENAI.COM")).containsExactlyElementsOf(pinned);
    }

    @Test
    void pinnedDns_refusesNonTargetHostFailClosed() {
        SsrfPinningClientFactory.PinnedDns dns =
                new SsrfPinningClientFactory.PinnedDns("api.openai.com", List.of(ip(1, 2, 3, 4)));

        // 非目标主机一律拒绝，不走系统 DNS（防重定向型 SSRF）；无网络
        assertThatThrownBy(() -> dns.lookup("evil.example.com"))
                .isInstanceOf(UnknownHostException.class);
    }

    // ---- PinnedAddressResolverGroup（reactor-netty 流式路径）：固定 IP + fail-closed ----

    private AddressResolver<InetSocketAddress> resolverFor(String host, int port, List<InetAddress> ips) {
        SsrfPinningClientFactory.PinnedAddressResolverGroup group =
                new SsrfPinningClientFactory.PinnedAddressResolverGroup(host, port, ips);
        return group.getResolver(ImmediateEventExecutor.INSTANCE);
    }

    @Test
    void pinnedResolver_resolveAllReturnsAllPinnedIpsWithPort() {
        List<InetAddress> pinned = List.of(ip(1, 2, 3, 4), ip(5, 6, 7, 8));
        AddressResolver<InetSocketAddress> resolver = resolverFor("api.openai.com", 443, pinned);

        Future<List<InetSocketAddress>> f =
                resolver.resolveAll(InetSocketAddress.createUnresolved("api.openai.com", 443));

        assertThat(f.isSuccess()).isTrue();
        List<InetSocketAddress> out = f.getNow();
        assertThat(out).hasSize(2);
        assertThat(out.get(0).getAddress()).isEqualTo(ip(1, 2, 3, 4));
        assertThat(out.get(0).getPort()).isEqualTo(443);
        assertThat(out.get(1).getAddress()).isEqualTo(ip(5, 6, 7, 8));
        assertThat(out.get(1).getPort()).isEqualTo(443);
    }

    @Test
    void pinnedResolver_resolveReturnsFirstPinnedIp() {
        List<InetAddress> pinned = List.of(ip(1, 2, 3, 4), ip(5, 6, 7, 8));
        AddressResolver<InetSocketAddress> resolver = resolverFor("api.openai.com", 443, pinned);

        Future<InetSocketAddress> f =
                resolver.resolve(InetSocketAddress.createUnresolved("api.openai.com", 443));

        assertThat(f.isSuccess()).isTrue();
        assertThat(f.getNow().getAddress()).isEqualTo(ip(1, 2, 3, 4));
        assertThat(f.getNow().getPort()).isEqualTo(443);
    }

    @Test
    void pinnedResolver_refusesNonTargetHostFailClosed() {
        AddressResolver<InetSocketAddress> resolver =
                resolverFor("api.openai.com", 443, List.of(ip(1, 2, 3, 4)));

        Future<List<InetSocketAddress>> f =
                resolver.resolveAll(InetSocketAddress.createUnresolved("evil.example.com", 443));

        assertThat(f.isSuccess()).isFalse();
        assertThat(f.cause()).isInstanceOf(UnknownHostException.class);
    }

    @Test
    void pinnedResolver_isSupportedAndIsResolvedSemantics() {
        AddressResolver<InetSocketAddress> resolver =
                resolverFor("api.openai.com", 443, List.of(ip(1, 2, 3, 4)));

        InetSocketAddress unresolved = InetSocketAddress.createUnresolved("api.openai.com", 443);
        InetSocketAddress resolved = new InetSocketAddress(ip(1, 2, 3, 4), 443);

        assertThat(resolver.isSupported(unresolved)).isTrue();
        assertThat(resolver.isResolved(unresolved)).isFalse();
        assertThat(resolver.isResolved(resolved)).isTrue();
    }

    // ---- 工厂装配：固定端点与非固定端点都能构造出 Builder（不抛、不发请求） ----

    @Test
    void factory_buildsBuildersWithoutThrowing() {
        ValidatedEndpoint pinned = new ValidatedEndpoint(
                "https://api.openai.com", "api.openai.com", 443, List.of(ip(1, 2, 3, 4)));
        ValidatedEndpoint local = new ValidatedEndpoint(
                "http://localhost:11434", "localhost", 11434, List.of());

        assertThat(SsrfPinningClientFactory.restClientBuilder(pinned)).isNotNull();
        assertThat(SsrfPinningClientFactory.webClientBuilder(pinned)).isNotNull();
        assertThat(SsrfPinningClientFactory.restClientBuilder(local)).isNotNull();
        assertThat(SsrfPinningClientFactory.webClientBuilder(local)).isNotNull();
    }
}
