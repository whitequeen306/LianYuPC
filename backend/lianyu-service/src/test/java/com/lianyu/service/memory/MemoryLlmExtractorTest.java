package com.lianyu.service.memory;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.lianyu.dao.entity.Message;
import com.lianyu.service.ai.AiChatService;
import com.lianyu.service.dto.ChatResult;
import java.util.List;
import org.junit.jupiter.api.Test;

class MemoryLlmExtractorTest {

    @Test
    void extract_returnsEmptyOnInvalidJson() {
        AiChatService aiChatService = mock(AiChatService.class);
        MemoryLlmExtractor extractor = new MemoryLlmExtractor(aiChatService, new com.fasterxml.jackson.databind.ObjectMapper());

        ChatResult result = ChatResult.builder().content("not-json").build();
        when(aiChatService.chatBlocking(any(), any())).thenReturn(result);

        Message msg = new Message();
        msg.setId(1L);
        msg.setRole("USER");
        msg.setContent("我最近在备考研究生");

        assertTrue(extractor.extract(1L, List.of(msg)).isEmpty());
    }
}
