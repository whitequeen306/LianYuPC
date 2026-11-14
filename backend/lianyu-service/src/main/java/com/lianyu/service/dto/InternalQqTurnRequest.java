package com.lianyu.service.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 网关 → 后端：一条 QQ 消息要跑成一次回合。 */
@Data
public class InternalQqTurnRequest {
    @NotBlank
    @Size(max = 32)
    private String channel;
    @NotBlank
    @Size(max = 64)
    private String externalId;
    @NotBlank
    @Size(max = 4000)
    private String text;
    /**
     * QQ 图片/表情包的字节（base64）。腾讯给的是带 rkey 的限时 URL，网关下载后交给我们，
     * 由后端转存到对象存储再走识图（识图只认自己的 objectKey，不对外部 URL 发请求）。
     */
    @Size(max = 8000000)
    private String imageBase64;
    /** 图片 MIME，缺省按 image/jpeg。 */
    @Size(max = 64)
    private String imageContentType;
}
