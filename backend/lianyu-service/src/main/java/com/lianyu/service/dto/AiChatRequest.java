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

    /** 期望的回复语言（zh / zh-TW / ja / en）；非空时启用输出语言门控 */
    private String expectedLanguage;

    /** 可选：限制生成长度（语音通话等短回复场景） */
    private Integer maxTokens;

    /** 当前轮用户消息附带图片时填充（MinIO objectKey 或公开路径），仅图片消息走多模态链路时使用 */
    private String imageUrl;

    /**
     * 为 true 时允许走平台 DEFAULT 池（仅记忆抽取 / 会话摘要 / 群聊@裁决等内部逻辑）。
     * 用户可见的聊天、主动消息、角色生成等不得置 true。
     */
    private boolean platformLogic;
}
