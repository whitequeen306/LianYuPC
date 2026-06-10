package com.lianyu.service.conversation;

import java.util.concurrent.ScheduledExecutorService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

/**
 * 用户修改现实城市后，由最近聊天的角色主动关心是否搬家/换城市。
 */
@Slf4j
@Service
public class CityChangeFollowUpScheduler {

    private final ScheduledExecutorService scheduledExecutorService;
    private final ConversationService conversationService;

    @Value("${lianyu.chat.city-change-followup.enabled:true}")
    private boolean enabled;

    @Autowired
    public CityChangeFollowUpScheduler(ScheduledExecutorService scheduledExecutorService,
                                       @Lazy ConversationService conversationService) {
        this.scheduledExecutorService = scheduledExecutorService;
        this.conversationService = conversationService;
    }

    public void schedule(Long userId, String previousCity, String newCity) {
        if (!enabled || userId == null || previousCity == null || newCity == null) {
            return;
        }
        if (previousCity.isBlank() || newCity.isBlank()
                || previousCity.trim().equalsIgnoreCase(newCity.trim())) {
            return;
        }
        scheduledExecutorService.execute(() -> {
            try {
                conversationService.sendCityChangeFollowUp(userId, previousCity.trim(), newCity.trim());
            } catch (Exception e) {
                log.warn("City change follow-up failed, userId={}, reason={}", userId, e.getMessage());
            }
        });
    }
}
