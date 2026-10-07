package com.lianyu.dao.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName(value = "character_diary", autoResultMap = true)
public class CharacterDiary {

    /** 定时算法生成（22:00/23:30 扫描）。 */
    public static final String SOURCE_SCHEDULED = "SCHEDULED";
    /** 角色聊天中调 write_diary 工具即兴生成。 */
    public static final String SOURCE_IMPROMPTU = "IMPROMPTU";

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long characterId;
    private Long userId;
    private String title;
    private String content;
    private String mood;
    private Long conversationId;
    private String source;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}