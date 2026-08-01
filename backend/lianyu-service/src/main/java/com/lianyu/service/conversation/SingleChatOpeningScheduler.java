package com.lianyu.service.conversation;

import com.lianyu.service.ai.background.AiBackgroundPublisher;
import com.lianyu.service.ai.background.AiBackgroundTask;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

/**
 * 新建单聊会话后：角色先发一条破冰话；若用户在配置时间内仍未回复，再发一条简短的关心，之后不再自动发。
 * 首条破冰同步（用户在等）；跟进走后台 AI 队列。
 */
@Slf4j
@Service
public class SingleChatOpeningScheduler {

    private final ScheduledExecutorService scheduledExecutorService;
    private final ConversationService conversationService;
    private final AiBackgroundPublisher aiBackgroundPublisher;

    @Value("${lianyu.chat.opening.enabled:true}")
    private boolean openingEnabled;

    @Value("${lianyu.chat.opening.followup-delay-ms:300000}")
    private long followupDelayMs;

    @Autowired
    public SingleChatOpeningScheduler(ScheduledExecutorService scheduledExecutorService,
                                      @Lazy ConversationService conversationService,
                                      AiBackgroundPublisher aiBackgroundPublisher) {
        this.scheduledExecutorService = scheduledExecutorService;
        this.conversationService = conversationService;
        this.aiBackgroundPublisher = aiBackgroundPublisher;
    }

    public void startSequence(Long userId, Long conversationId) {
        if (!openingEnabled) {
            return;
        }
        scheduledExecutorService.execute(() -> {
            try {
                conversationService.sendColdOpenFirstLine(userId, conversationId);
            } catch (Exception e) {
                log.warn("Cold open first line failed, convId={}, reason={}", conversationId, e.getMessage());
                return;
            }
            scheduledExecutorService.schedule(() -> {
                try {
                    aiBackgroundPublisher.publish(AiBackgroundTask.coldOpenFollowUp(userId, conversationId));
                } catch (Exception e) {
                    log.warn("Cold open follow-up enqueue failed, convId={}, reason={}",
                            conversationId, e.getMessage());
                }
            }, Math.max(60_000L, followupDelayMs), TimeUnit.MILLISECONDS);
        });
    }
}
