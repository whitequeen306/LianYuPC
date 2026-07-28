package com.lianyu.service.graph;

import static org.assertj.core.api.Assertions.assertThat;

import com.lianyu.dao.entity.Message;
import org.junit.jupiter.api.Test;

class MessageModelContentTest {

    @Test
    void prefersContextContentWhenPresent() {
        Message msg = new Message();
        msg.setContent("我们进行了3分24秒的语音通话");
        msg.setContextContent("（用户和角色进行了语音通话（聊了天气））");
        assertThat(MessageModelContent.forModel(msg))
                .isEqualTo("（用户和角色进行了语音通话（聊了天气））");
    }

    @Test
    void fallsBackToContent() {
        Message msg = new Message();
        msg.setContent("普通消息");
        assertThat(MessageModelContent.forModel(msg)).isEqualTo("普通消息");
    }
}
