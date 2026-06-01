package com.lianyu.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lianyu.dao.entity.WebPushSubscription;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import nl.martijndwars.webpush.Notification;
import nl.martijndwars.webpush.PushService;
import org.apache.http.HttpResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class WebPushService {

    private final NotificationService notificationService;
    private final ObjectMapper objectMapper;

    @Value("${lianyu.push.enabled:false}")
    private boolean pushEnabled;
    @Value("${lianyu.push.vapid.public-key:}")
    private String vapidPublicKey;
    @Value("${lianyu.push.vapid.private-key:}")
    private String vapidPrivateKey;
    @Value("${lianyu.push.vapid.subject:mailto:admin@lianyu.local}")
    private String vapidSubject;

    public void sendToUser(Long userId, String title, String body, Long conversationId) {
        if (!isPushReady()) {
            return;
        }
        List<WebPushSubscription> subscriptions = notificationService.listEnabledSubscriptions(userId);
        if (subscriptions.isEmpty()) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("title", title);
        payload.put("body", body);
        payload.put("conversationId", conversationId);
        payload.put("url", notificationService.getPushClickUrlPrefix() + (conversationId == null ? "" : conversationId));

        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            log.warn("web push payload encode failed: {}", e.getMessage());
            return;
        }

        PushService pushService;
        try {
            pushService = new PushService(vapidPublicKey, vapidPrivateKey, vapidSubject);
        } catch (Exception e) {
            log.warn("web push init failed: {}", e.getMessage());
            return;
        }

        for (WebPushSubscription sub : subscriptions) {
            try {
                Notification notification = new Notification(
                        sub.getEndpoint(),
                        sub.getP256dh(),
                        sub.getAuth(),
                        json.getBytes(StandardCharsets.UTF_8)
                );
                HttpResponse response = pushService.send(notification);
                int status = response.getStatusLine().getStatusCode();
                if (status == 404 || status == 410) {
                    notificationService.disableSubscriptionByEndpoint(sub.getEndpoint());
                    log.info("web push subscription disabled: endpoint={}", sub.getEndpoint());
                } else if (status < 200 || status >= 300) {
                    log.warn("web push non-2xx status={}, endpoint={}", status, sub.getEndpoint());
                }
            } catch (Exception e) {
                log.warn("web push send failed: endpoint={}, reason={}", sub.getEndpoint(), e.getMessage());
            }
        }
    }

    private boolean isPushReady() {
        return pushEnabled
                && vapidPublicKey != null && !vapidPublicKey.isBlank()
                && vapidPrivateKey != null && !vapidPrivateKey.isBlank();
    }
}
