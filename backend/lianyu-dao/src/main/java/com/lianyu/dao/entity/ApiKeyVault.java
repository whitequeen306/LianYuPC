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
    private Integer enabled;
    private String remark;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
