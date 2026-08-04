package com.lianyu.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lianyu.dao.entity.Conversation;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ConversationMapper extends BaseMapper<Conversation> {

    /**
     * 按最后一条消息时间倒序查询单聊候选会话。
     * 无消息的会话退化为按创建时间排序（COALESCE），保证新建会话也能被扫描到。
     */
    @Select("""
            SELECT c.id, c.user_id, c.character_id, c.mode, c.title, c.created_at
            FROM conversation c
            WHERE c.mode = 'SINGLE'
              AND c.character_id IS NOT NULL
            ORDER BY COALESCE(
                (SELECT MAX(m.created_at) FROM message m WHERE m.conversation_id = c.id),
                c.created_at
            ) DESC
            LIMIT #{limit}
            """)
    List<Conversation> selectSingleConversationsOrderByLastMessage(@Param("limit") int limit);
}
