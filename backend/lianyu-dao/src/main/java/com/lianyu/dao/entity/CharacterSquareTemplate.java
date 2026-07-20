package com.lianyu.dao.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.lianyu.common.handler.JacksonTypeHandler;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@TableName(value = "character_square_template", autoResultMap = true)
public class CharacterSquareTemplate {
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 稳定 i18n / 头像文件名键 */
    private String slug;
    private String name;
    private String summary;
    private String avatarUrl;
    private String promptTemplate;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> settingsJson;
    /** JSON 数组，运行时解析为标签列表 */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Object tagsJson;
    private Integer isEnabled;
    private Integer sortOrder;
    /** 累计被用户从广场添加的次数 */
    private Long addCount;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
