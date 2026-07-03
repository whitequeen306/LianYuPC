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

    /** 当前轮用户消息附带图片时填充（MinIO objectKey 或公开路径），仅图片消息走多模态链路时使用 */
    private String imageUrl;
}
