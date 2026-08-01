package com.lianyu.service.ai.background;

import com.lianyu.service.character.CharacterDiaryService;
import com.lianyu.service.conversation.ConversationService;
import com.lianyu.service.conversation.VoiceCallService;
import com.lianyu.service.moments.MomentsCommentOrchestrator;
import com.lianyu.service.moments.MomentsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class AiBackgroundConsumer {

    private final MomentsCommentOrchestrator momentsCommentOrchestrator;
    private final MomentsService momentsService;
    private final CharacterDiaryService characterDiaryService;
    private final ConversationService conversationService;
    private final VoiceCallService voiceCallService;

    @RabbitListener(
            queues = "ai.background.queue",
            containerFactory = "aiBackgroundListenerContainerFactory",
            concurrency = "${lianyu.mq.ai-background.listener-concurrency:2-4}"
    )
    public void onTask(AiBackgroundTask task) {
        if (task == null || task.type() == null) {
            return;
        }
        log.info("AI background consume: type={}, userId={}, convId={}, postId={}",
                task.type(), task.userId(), task.conversationId(), task.postId());
        try {
            switch (task.type()) {
                case MOMENTS_PEER_COMMENT -> momentsCommentOrchestrator.processPeerCommentJob(task);
                case MOMENTS_AUTHOR_REPLY -> momentsCommentOrchestrator.processAuthorReplyJob(task);
                case MOMENTS_POST -> momentsService.processMomentsPostJob(task);
                case CHARACTER_DIARY -> characterDiaryService.processDiaryJob(task);
                case COLD_OPEN_FOLLOWUP -> conversationService.sendColdOpenFollowUpIfStillSilent(
                        task.userId(), task.conversationId());
                case CITY_CHANGE_FOLLOWUP -> conversationService.sendCityChangeFollowUp(
                        task.userId(), task.previousCity(), task.newCity());
                case VOICE_CALL_SUMMARY -> voiceCallService.processVoiceCallSummaryJob(task);
                default -> log.warn("AI background unknown type: {}", task.type());
            }
        } catch (Exception e) {
            log.error("AI background job failed: type={}, postId={}, convId={}, reason={}",
                    task.type(), task.postId(), task.conversationId(), e.toString());
            throw e instanceof RuntimeException re ? re : new RuntimeException(e);
        }
    }
}
