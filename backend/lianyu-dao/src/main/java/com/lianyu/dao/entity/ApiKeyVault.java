package com.lianyu.dao.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("api_key_vault")
public class ApiKeyVault {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String vaultScope;
    private String provider;
    private String apiKeyEncrypted;
    private String keyVersion;
    private String baseUrl;
    private String modelDefault;
    /** text（默认，聊天文本模型）| vision（识图/VL 专用，modelDefault 即识图模型） */
    private String purpose;
    /** @deprecated 识图改走 purpose=vision 的整条 vault 或平台默认；列保留，不再使用 */
    private String visionModelDefault;
    private Integer enabled;
    private String remark;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
