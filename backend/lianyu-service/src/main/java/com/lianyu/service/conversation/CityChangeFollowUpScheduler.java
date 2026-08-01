package com.lianyu.service.conversation;

import com.lianyu.service.ai.background.AiBackgroundPublisher;
import com.lianyu.service.ai.background.AiBackgroundTask;
import java.util.concurrent.ScheduledExecutorService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 用户修改现实城市后，由最近聊天的角色主动关心是否搬家/换城市（后台 AI 队列）。
 */
@Slf4j
@Service
public class CityChangeFollowUpScheduler {

    private final ScheduledExecutorService scheduledExecutorService;
    private final AiBackgroundPublisher aiBackgroundPublisher;

    @Value("${lianyu.chat.city-change-followup.enabled:true}")
    private boolean enabled;

    @Autowired
    public CityChangeFollowUpScheduler(ScheduledExecutorService scheduledExecutorService,
                                       AiBackgroundPublisher aiBackgroundPublisher) {
        this.scheduledExecutorService = scheduledExecutorService;
        this.aiBackgroundPublisher = aiBackgroundPublisher;
    }

    public void schedule(Long userId, String previousCity, String newCity) {
        if (!enabled || userId == null || previousCity == null || newCity == null) {
            return;
        }
        if (previousCity.isBlank() || newCity.isBlank()
                || previousCity.trim().equalsIgnoreCase(newCity.trim())) {
            return;
        }
        String prev = previousCity.trim();
        String next = newCity.trim();
        scheduledExecutorService.execute(() -> {
            try {
                aiBackgroundPublisher.publish(AiBackgroundTask.cityChangeFollowUp(userId, prev, next));
            } catch (Exception e) {
                log.warn("City change follow-up enqueue failed, userId={}, reason={}", userId, e.getMessage());
            }
        });
    }
}
