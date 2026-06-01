package com.lianyu.dao.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("group_member")
public class GroupMember {
    private Long conversationId;
    private Long characterId;
    private Integer sortOrder;
}
