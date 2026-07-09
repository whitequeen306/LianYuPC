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
            LEFT JOIN (
                SELECT conversation_id, MAX(created_at) AS last_message_at
                FROM message
                GROUP BY conversation_id
            ) m ON c.id = m.conversation_id
            WHERE c.mode = 'SINGLE'
              AND c.character_id IS NOT NULL
            ORDER BY COALESCE(m.last_message_at, c.created_at) DESC
            LIMIT #{limit}
            """)
    List<Conversation> selectSingleConversationsOrderByLastMessage(@Param("limit") int limit);
}
