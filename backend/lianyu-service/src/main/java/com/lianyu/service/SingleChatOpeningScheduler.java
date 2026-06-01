package com.lianyu.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 新建单聊会话后：角色先发一条破冰话；若用户在配置时间内仍未回复，再发一条简短的关心，之后不再自动发。
 */
@Slf4j
@Service
public class SingleChatOpeningScheduler {

    private final ScheduledExecutorService scheduledExecutorService;
    private final ConversationService conversationService;

    @Value("${lianyu.chat.opening.enabled:true}")
    private boolean openingEnabled;

    @Value("${lianyu.chat.opening.followup-delay-ms:300000}")
    private long followupDelayMs;

    @Autowired
    public SingleChatOpeningScheduler(ScheduledExecutorService scheduledExecutorService,
                                      @Lazy ConversationService conversationService) {
        this.scheduledExecutorService = scheduledExecutorService;
        this.conversationService = conversationService;
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
                    conversationService.sendColdOpenFollowUpIfStillSilent(userId, conversationId);
                } catch (Exception e) {
                    log.warn("Cold open follow-up failed, convId={}, reason={}", conversationId, e.getMessage());
                }
            }, Math.max(60_000L, followupDelayMs), TimeUnit.MILLISECONDS);
        });
    }
}
