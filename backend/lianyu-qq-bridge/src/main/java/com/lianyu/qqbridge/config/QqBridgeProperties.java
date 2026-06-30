package com.lianyu.qqbridge.config;

import com.lianyu.common.constant.AiConstants;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * QQ 桥配置（前缀 {@code lianyu.qq-bridge}）。默认 {@code enabled=false}。
 * <p>
 * 单人模式（Phase 1）：{@code binding} 把一个 QQ 号路由到一个已有的 LianYu 用户/会话。
 * provider 默认 {@link AiConstants#PLATFORM_PROVIDER}，即走运营者平台共享 AI key，QQ 用户无需自有 vault。
 */
@Data
@Component
@ConfigurationProperties(prefix = "lianyu.qq-bridge")
public class QqBridgeProperties {

    /** 总开关。关闭时整模块不装配，启动不连 NapCat。 */
    private boolean enabled = false;

    private Napcat napcat = new Napcat();
    private Binding binding = new Binding();
    private Reply reply = new Reply();

    @Data
    public static class Napcat {
        /** NapCat 正向 WebSocket 地址，例如 {@code ws://127.0.0.1:3001}。access-token 以 query 形式自动追加。 */
        private String wsUrl = "ws://127.0.0.1:3001";
        /** NapCat 网络配置里设置的鉴权 token（对应 websocketServers[].token）。 */
        private String accessToken = "";
        /** 单次握手超时秒数。 */
        private int connectTimeoutSeconds = 10;
        /** 超过该秒数未收到任何帧则认为连接僵死，强制重连（应 ≥ NapCat heartInterval 的 2~3 倍）。 */
        private int heartbeatTimeoutSeconds = 90;
    }

    @Data
    public static class Binding {
        /** 仅处理来自该 QQ 号的私聊消息（单人模式白名单）。为 0 表示不限制来源。 */
        private long qqUserId = 0L;
        /** 路由到的 LianYu 用户 ID（需已存在并拥有 conversationId 对应会话）。 */
        private long lianyuUserId = 0L;
        /** 路由到的 LianYu 会话 ID。 */
        private long conversationId = 0L;
        /** AI provider，默认平台共享 key。 */
        private String provider = AiConstants.PLATFORM_PROVIDER;
        /** 可选模型覆盖；留空则用会话/角色默认。 */
        private String model = "";
        /** 允许响应的群号；为空则不响应任何群消息。 */
        private List<Long> allowGroups = List.of();
    }

    @Data
    public static class Reply {
        /** 是否把角色多条分片回复逐条发回 QQ（模拟多条气泡）。 */
        private boolean sendAllPieces = true;
        /** 多条气泡之间的间隔毫秒，避免 QQ 频控。 */
        private long maxPieceGapMs = 800L;
        /** 单条回复最大长度，超出则截断（QQ 单条消息上限约 5000 字符）。 */
        private int maxPieceChars = 2000;
        /** 角色调用失败时发回 QQ 的兜底文案，不为空才发。 */
        private String fallbackText = "（……信号好像不太好，稍后再和我说吧）";
    }
}
