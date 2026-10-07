package com.lianyu.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lianyu.dao.dto.ConversationActivityRow;
import com.lianyu.dao.entity.Conversation;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
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

    /**
     * 查询指定时间之后有过用户聊天的单聊会话，按窗口内用户消息数降序。
     * 日记触发用：候选 = 当天真的聊过天的会话（用户消息数 > 0，纯角色主动发言不计）。
     */
    @Select("""
            SELECT c.id AS conversationId, c.user_id AS userId, c.character_id AS characterId,
                   SUM(m.role = 'USER') AS userMsgCount, MAX(m.created_at) AS lastActivityAt
            FROM conversation c
            JOIN message m ON m.conversation_id = c.id
            WHERE c.mode = 'SINGLE'
              AND c.character_id IS NOT NULL
              AND m.created_at >= #{since}
            GROUP BY c.id, c.user_id, c.character_id
            HAVING userMsgCount > 0
            ORDER BY userMsgCount DESC, lastActivityAt DESC
            LIMIT #{limit}
            """)
    List<ConversationActivityRow> selectSingleConversationsActiveSince(@Param("since") LocalDateTime since,
                                                                      @Param("limit") int limit);
}
