package com.lianyu.service.ai.background;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.lianyu.common.base.ErrorCode;
import com.lianyu.common.exception.BusinessException;
import com.lianyu.service.character.CharacterDiaryService;
import com.lianyu.service.conversation.ConversationService;
import com.lianyu.service.conversation.ProactiveMessageService;
import com.lianyu.service.conversation.VoiceCallService;
import com.lianyu.service.moments.MomentsCommentOrchestrator;
import com.lianyu.service.moments.MomentsService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class AiBackgroundConsumerTest {

    @Mock MomentsCommentOrchestrator momentsCommentOrchestrator;
    @Mock MomentsService momentsService;
    @Mock CharacterDiaryService characterDiaryService;
    @Mock ConversationService conversationService;
    @Mock ProactiveMessageService proactiveMessageService;
    @Mock VoiceCallService voiceCallService;

    private AiBackgroundConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new AiBackgroundConsumer(
                momentsCommentOrchestrator,
                momentsService,
                characterDiaryService,
                conversationService,
                proactiveMessageService,
                voiceCallService);
        // 重试退避改成 1ms，避免单测等待
        ReflectionTestUtils.setField(consumer, "retryDelayMs", 1L);
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

    // ── 重试语义：agent 重启窗口内的可恢复错误 ──

    @Test
    void retriesAgentUnavailableThenSucceeds() {
        AiBackgroundTask task = AiBackgroundTask.momentsPost(7L, 11L, 13L);
        doThrow(new BusinessException(ErrorCode.AI_PROVIDER_ERROR, "prompt 组装失败，请稍后再试"))
                .doNothing()
                .when(momentsService).processMomentsPostJob(task);

        consumer.onTask(task);

        verify(momentsService, times(2)).processMomentsPostJob(task);
    }

    @Test
    void retriesDispatchFailureThenSucceeds() {
        AiBackgroundTask task = AiBackgroundTask.momentsPost(7L, 11L, 13L);
        doThrow(new BusinessException(ErrorCode.AI_PROVIDER_ERROR, "消息发送失败，请稍后再试"))
                .doNothing()
                .when(momentsService).processMomentsPostJob(task);

        consumer.onTask(task);

        verify(momentsService, times(2)).processMomentsPostJob(task);
    }

    @Test
    void nonTransientBusinessErrorFailsImmediately() {
        AiBackgroundTask task = AiBackgroundTask.momentsPost(7L, 11L, 13L);
        doThrow(new BusinessException(ErrorCode.BAD_REQUEST, "内容不合法"))
                .when(momentsService).processMomentsPostJob(task);

        assertThrows(BusinessException.class, () -> consumer.onTask(task));
        verify(momentsService, times(1)).processMomentsPostJob(task);
    }

    @Test
    void givesUpAfterThreeAttemptsAndRethrows() {
        AiBackgroundTask task = AiBackgroundTask.momentsPost(7L, 11L, 13L);
        doThrow(new BusinessException(ErrorCode.AI_PROVIDER_ERROR, "prompt 组装失败，请稍后再试"))
                .when(momentsService).processMomentsPostJob(task);

        assertThrows(BusinessException.class, () -> consumer.onTask(task));
        verify(momentsService, times(3)).processMomentsPostJob(task);
    }

    @Test
    void transientClassifierWalksCauseChain() {
        IllegalStateException wrapper = new IllegalStateException("wrapped",
                new BusinessException(ErrorCode.AI_PROVIDER_ERROR, "prompt 组装失败，请稍后再试"));
        assertTrue(AiBackgroundConsumer.isAgentTransient(wrapper));
    }
}
