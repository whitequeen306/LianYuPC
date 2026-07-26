package com.lianyu.service.graph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lianyu.dao.entity.Message;
import com.lianyu.service.dto.MessageDto;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChatTurnMessageAssemblerTest {

    private final ChatTurnMessageAssembler assembler = new ChatTurnMessageAssembler();

    @Test
    void assemble_wrapsCurrentUserMessageAndKeepsSystemFirst() {
        Message history = new Message();
        history.setId(10L);
        history.setRole("USER");
        history.setContent("旧内容");

        List<MessageDto> messages = assembler.assemble(
                "SYSTEM",
                List.of(history),
                10L,
                "新内容",
                null);

        assertEquals(2, messages.size());
        assertEquals("system", messages.get(0).getRole());
        assertEquals("SYSTEM", messages.get(0).getContent());
        assertTrue(messages.get(1).getContent().contains("新内容"));
        assertTrue(messages.get(1).getContent().contains("<user_message"));
    }

    @Test
    void assemble_imageHistoryUsesTextPlaceholderWithoutImageUrl() {
        Message history = new Message();
        history.setId(11L);
        history.setRole("USER");
        history.setContent("（用户发了一张图片（一只橘猫））");
        history.setImageUrl("/api/public/files/chat-images/demo.jpg");

        List<MessageDto> messages = assembler.assemble(
                "SYSTEM",
                List.of(history),
                null,
                null,
                null);

        assertEquals(2, messages.size());
        assertNull(messages.get(1).getImageUrl());
        assertEquals("（用户发了一张图片（一只橘猫））", messages.get(1).getContent());
    }

    @Test
    void assemble_imageOnlyCurrentTurn_rewritesPlaceholderWithoutImageUrl() {
        Message history = new Message();
        history.setId(11L);
        history.setRole("USER");
        history.setContent("（用户发了一张图片）");
        history.setImageUrl("/api/public/files/chat-images/demo.jpg");

        List<MessageDto> messages = assembler.assemble(
                "SYSTEM",
                List.of(history),
                11L,
                "<user_message trusted=\"false\"></user_message>",
                null);

        assertEquals(2, messages.size());
        assertNull(messages.get(1).getImageUrl());
        assertTrue(messages.get(1).getContent().contains("用户发送了一张图片"));
    }
}
