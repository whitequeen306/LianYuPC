package com.lianyu.service.ai.background;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.lianyu.service.character.CharacterDiaryService;
import com.lianyu.service.conversation.ConversationService;
import com.lianyu.service.conversation.VoiceCallService;
import com.lianyu.service.moments.MomentsCommentOrchestrator;
import com.lianyu.service.moments.MomentsService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AiBackgroundConsumerTest {

    @Mock MomentsCommentOrchestrator momentsCommentOrchestrator;
    @Mock MomentsService momentsService;
    @Mock CharacterDiaryService characterDiaryService;
    @Mock ConversationService conversationService;
    @Mock VoiceCallService voiceCallService;

    private AiBackgroundConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new AiBackgroundConsumer(
                momentsCommentOrchestrator,
                momentsService,
                characterDiaryService,
                conversationService,
                voiceCallService);
    }

    @Test
    void dispatchesPeerComment() {
        AiBackgroundTask task = AiBackgroundTask.momentsPeerComment(1L, 2L, 0, 0, List.of(2L));
        consumer.onTask(task);
        verify(momentsCommentOrchestrator).processPeerCommentJob(task);
        verify(momentsService, never()).processMomentsPostJob(any());
    }

    @Test
    void dispatchesMomentsPost() {
        AiBackgroundTask task = AiBackgroundTask.momentsPost(1L, 2L, 3L);
        consumer.onTask(task);
        verify(momentsService).processMomentsPostJob(task);
    }

    @Test
    void ignoresNullTask() {
        consumer.onTask(null);
        verify(momentsCommentOrchestrator, never()).processPeerCommentJob(any());
    }
}
