package com.lianyu.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lianyu.dao.entity.Message;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface MessageMapper extends BaseMapper<Message> {

    @Select("""
            <script>
            SELECT m.id, m.seq, m.conversation_id, m.role, m.character_id, m.content, m.tokens, m.created_at
            FROM message m
            INNER JOIN (
                SELECT conversation_id, MAX(seq) AS max_seq
                FROM message
                WHERE conversation_id IN
                <foreach collection='conversationIds' item='cid' open='(' separator=',' close=')'>
                    #{cid}
                </foreach>
                GROUP BY conversation_id
            ) x ON m.conversation_id = x.conversation_id AND m.seq = x.max_seq
            </script>
            """)
    List<Message> selectLatestByConversationIds(@Param("conversationIds") List<Long> conversationIds);

    @Select("""
            <script>
            SELECT m.id, m.seq, m.conversation_id, m.role, m.character_id, m.content, m.tokens, m.created_at
            FROM message m
            INNER JOIN (
                SELECT conversation_id, MAX(seq) AS max_seq
                FROM message
                WHERE role = 'USER'
                  AND conversation_id IN
                <foreach collection='conversationIds' item='cid' open='(' separator=',' close=')'>
                    #{cid}
                </foreach>
                GROUP BY conversation_id
            ) x ON m.conversation_id = x.conversation_id AND m.seq = x.max_seq
            </script>
            """)
    List<Message> selectLatestUserByConversationIds(@Param("conversationIds") List<Long> conversationIds);
}
