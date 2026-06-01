package com.lianyu.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lianyu.common.base.ErrorCode;
import com.lianyu.common.exception.BusinessException;
import com.lianyu.dao.entity.Conversation;
import com.lianyu.dao.mapper.ConversationMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ConversationAccessService {

    private final ConversationMapper conversationMapper;

    public void assertUserOwnsConversation(long userId, long conversationId) {
        Conversation conversation = conversationMapper.selectOne(new LambdaQueryWrapper<Conversation>()
                .eq(Conversation::getId, conversationId)
                .eq(Conversation::getUserId, userId)
                .last("LIMIT 1"));
        if (conversation == null) {
            throw new BusinessException(ErrorCode.CONVERSATION_NOT_FOUND);
        }
    }
}
