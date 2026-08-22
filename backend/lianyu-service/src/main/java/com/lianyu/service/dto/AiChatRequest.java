package com.lianyu.service.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import java.util.Map;
import lombok.Data;

@Data
public class AiChatRequest {
    @NotBlank
    private String provider;

    private String model;
    /** Optional vision/VL model override for image analysis stage */
    /** @deprecated 识图固定走平台多模态，该字段保留兼容但不再生效 */
    private String visionModel;
    private Double temperature;

    @NotEmpty
    private List<MessageDto> messages;

    /**
     * 非空时为本轮对话启用 ToolManager 注册的工具（时间/天气/记忆等），userId 由 AiChatService 注入。
     */
    private Long chatToolCharacterId;

    /** 角色 settings，供 get_weather 等解析默认城市 */
    private Map<String, Object> toolCharacterSettings;

    /** 本轮发言角色名，经工具桥下发给桌面端控制条 */
    private String chatToolCharacterName;

    /** 本轮发言角色头像路径（相对或绝对），桌面端会再 resolve 成可加载 URL */
    private String chatToolCharacterAvatarUrl;

    /** 期望的回复语言（zh / zh-TW / ja / en）；非空时启用输出语言门控 */
    private String expectedLanguage;

    /** 可选：限制生成长度（语音通话等短回复场景） */
    private Integer maxTokens;

    /** 当前轮用户消息附带图片时填充（MinIO objectKey 或公开路径），仅图片消息走多模态链路时使用 */
    private String imageUrl;

    /**
     * 为 true 时走 {@code ai-background} bulkhead（朋友圈/日记/主动跟进/记忆摘要等），
     * 与前台交互池隔离，避免后台风暴饿死聊天。
     */
    private boolean background;
}
