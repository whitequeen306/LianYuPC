package com.lianyu.service.ai.background;

import com.lianyu.common.base.ErrorCode;
import com.lianyu.common.exception.BusinessException;
import com.lianyu.service.character.CharacterDiaryService;
import com.lianyu.service.conversation.ConversationService;
import com.lianyu.service.conversation.ProactiveMessageService;
import com.lianyu.service.conversation.VoiceCallService;
import com.lianyu.service.moments.MomentsCommentOrchestrator;
import com.lianyu.service.moments.MomentsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * AI 后台任务消费者。
 *
 * <p><b>为什么要重试</b>：容器工厂配置了 `defaultRequeueRejected(false)`（失败进 DLQ、
 * 不自动重投）。agent 滚动更新时有三秒左右的不可用窗口，落在窗口内的任务会直接失败进 DLQ
 * ——生产实测（2026-10-20）一次 agent 重启导致 1 条 MOMENTS_POST 丢失。
 *
 * <p>因此对「agent 侧暂时不可用」这一类<em>可恢复</em>错误做有界重试（3 次、递增退避），
 * 其余错误（业务错、内容校验失败等）立即失败进 DLQ，不做无意义重试。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiBackgroundConsumer {

    private static final int MAX_ATTEMPTS = 3;

    private final MomentsCommentOrchestrator momentsCommentOrchestrator;
    private final MomentsService momentsService;
    private final CharacterDiaryService characterDiaryService;
    private final ConversationService conversationService;
    private final ProactiveMessageService proactiveMessageService;
    private final VoiceCallService voiceCallService;

    /** 首次重试等待；第 n 次重试等待 n × 该值。测试可覆盖。 */
    @Value("${lianyu.mq.ai-background.retry-delay-ms:1500}")
    private long retryDelayMs = 1500L;

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

        RuntimeException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                dispatch(task);
                return;
            } catch (Exception e) {
                last = e instanceof RuntimeException re ? re : new RuntimeException(e);
                boolean retryable = attempt < MAX_ATTEMPTS && isAgentTransient(e);
                if (!retryable) {
                    log.error("AI background job failed: type={}, postId={}, convId={}, attempt={}/{}, reason={}",
                            task.type(), task.postId(), task.conversationId(),
                            attempt, MAX_ATTEMPTS, e.toString());
                    throw last;
                }
                long delay = Math.max(1L, retryDelayMs) * attempt;
                log.warn("AI background job failed (agent unavailable?): type={}, convId={}, attempt={}/{}, "
                                + "retry in {}ms, reason={}",
                        task.type(), task.conversationId(), attempt, MAX_ATTEMPTS, delay, e.toString());
                if (!sleep(delay)) {
                    throw last;
                }
            }
        }
        throw last == null ? new IllegalStateException("AI background dispatch failed") : last;
    }

    private void dispatch(AiBackgroundTask task) {
        switch (task.type()) {
            case MOMENTS_PEER_COMMENT -> momentsCommentOrchestrator.processPeerCommentJob(task);
            case MOMENTS_AUTHOR_REPLY -> momentsCommentOrchestrator.processAuthorReplyJob(task);
            case MOMENTS_POST -> momentsService.processMomentsPostJob(task);
            case CHARACTER_DIARY -> characterDiaryService.processDiaryJob(task);
            case COLD_OPEN_FOLLOWUP -> proactiveMessageService.sendColdOpenFollowUpIfStillSilent(
                    task.userId(), task.conversationId());
            case CITY_CHANGE_FOLLOWUP -> proactiveMessageService.sendCityChangeFollowUp(
                    task.userId(), task.previousCity(), task.newCity());
            case VOICE_CALL_SUMMARY -> voiceCallService.processVoiceCallSummaryJob(task);
            default -> log.warn("AI background unknown type: {}", task.type());
        }
    }

    /**
     * 只重试「agent 暂时不可用」：prompt 组装/派发失败映射为 AI_PROVIDER_ERROR。
     * 模型自身的业务错误（如 key 无效）不在此列，重试没有意义。
     */
    static boolean isAgentTransient(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof BusinessException be
                    && be.getErrorCode() == ErrorCode.AI_PROVIDER_ERROR) {
                String message = be.getMessage();
                if (message == null) {
                    return false;
                }
                return message.contains("prompt 组装失败")
                        || message.contains("消息发送失败")
                        || message.contains("服务暂时不可用");
            }
        }
        return false;
    }

    /** @return false 表示被中断（调用方应停止重试并把中断标志还原）。 */
    private static boolean sleep(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
