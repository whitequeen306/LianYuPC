package com.lianyu.service.notification;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.lianyu.dao.entity.UserNotification;
import com.lianyu.dao.entity.WebPushSubscription;
import com.lianyu.dao.mapper.UserNotificationMapper;
import com.lianyu.dao.mapper.WebPushSubscriptionMapper;
import com.lianyu.service.dto.MarkNotificationReadRequest;
import com.lianyu.service.dto.NotificationResponse;
import com.lianyu.service.dto.PushNotifyTask;
import com.lianyu.service.dto.PushSubscriptionRequest;
import com.lianyu.service.dto.UnreadCountResponse;
import java.time.LocalDateTime;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private static final String EXCHANGE = "lianyu.exchange";
    private static final String RK_EVENT_BROADCAST = "event.broadcast";

    private final UserNotificationMapper notificationMapper;
    private final WebPushSubscriptionMapper subscriptionMapper;
    private final SimpMessagingTemplate messagingTemplate;
    private final RabbitTemplate rabbitTemplate;

    @Value("${lianyu.push.enabled:false}")
    private boolean pushEnabled;

    @Value("${lianyu.push.click-url-prefix:#/chat/}")
    private String pushClickUrlPrefix;

    @Value("${lianyu.push.vapid.public-key:}")
    private String vapidPublicKey;

    public NotificationResponse notifyProactiveMessage(Long userId,
                                                       Long conversationId,
                                                       Long characterId,
                                                       String characterName,
                                                       String preview) {
        return notifyAssistantMessage(userId, conversationId, characterId, characterName, preview, "PROACTIVE_MESSAGE");
    }

    /**
     * 群聊内角色发言（含主动冒泡、自动回复）；未读应落在群会话上，而非单聊角色卡片。
     */
    public NotificationResponse notifyMomentPost(Long userId,
                                                 Long conversationId,
                                                 Long characterId,
                                                 String characterName,
                                                 String preview) {
        String speaker = (characterName == null || characterName.isBlank()) ? "角色" : characterName;
        String title = speaker + " 发布了新动态";
        return createAndPushNotification(userId, conversationId, characterId, title, preview, "MOMENT_NEW");
    }

    public NotificationResponse notifyMomentComment(Long userId,
                                                    Long conversationId,
                                                    Long characterId,
                                                    String characterName,
                                                    String preview,
                                                    Long postId) {
        String speaker = (characterName == null || characterName.isBlank()) ? "角色" : characterName;
        String title = speaker + " 评论了朋友圈";
        return createAndPushNotification(userId, conversationId, characterId, title, preview, "MOMENT_COMMENT");
    }

    public NotificationResponse notifyGroupMessage(Long userId,
                                                   Long conversationId,
                                                   Long characterId,
                                                   String characterName,
                                                   String preview) {
        String speaker = (characterName == null || characterName.isBlank()) ? "角色" : characterName;
        String title = speaker + " 在群聊中发言";
        return createAndPushNotification(userId, conversationId, characterId, title, preview, "GROUP_MESSAGE");
    }

    public NotificationResponse notifyAssistantMessage(Long userId,
                                                       Long conversationId,
                                                       Long characterId,
                                                       String characterName,
                                                       String preview,
                                                       String type) {
        String title = (characterName == null || characterName.isBlank() ? "角色" : characterName) + " 给你发来消息";
        return createAndPushNotification(userId, conversationId, characterId, title, preview, type);
    }

    private NotificationResponse createAndPushNotification(Long userId,
                                                         Long conversationId,
                                                         Long characterId,
                                                         String title,
                                                         String preview,
                                                         String type) {
        UserNotification notification = new UserNotification();
        notification.setUserId(userId);
        notification.setConversationId(conversationId);
        notification.setCharacterId(characterId);
        notification.setType(type == null || type.isBlank() ? "MESSAGE" : type);
        notification.setTitle(title == null || title.isBlank() ? "新消息" : title);
        notification.setContentPreview(trimPreview(preview));
        notification.setIsRead(0);
        notificationMapper.insert(notification);

        NotificationResponse response = toResponse(notification);
        Long unread = getUnreadCountRaw(userId);
        messagingTemplate.convertAndSendToUser(userId.toString(), "/queue/notifications", response);
        messagingTemplate.convertAndSendToUser(userId.toString(), "/queue/notification-unread",
                new UnreadCountResponse(unread));

        if (pushEnabled && hasAnySubscription(userId)) {
            rabbitTemplate.convertAndSend(EXCHANGE, RK_EVENT_BROADCAST,
                    new PushNotifyTask(
                            userId,
                            notification.getTitle(),
                            notification.getContentPreview(),
                            conversationId
                    ));
        }
        return response;
    }

    public List<NotificationResponse> list(Long userId, boolean unreadOnly, int limit) {
        int realLimit = Math.min(Math.max(1, limit), 100);
        LambdaQueryWrapper<UserNotification> qw = new LambdaQueryWrapper<UserNotification>()
                .eq(UserNotification::getUserId, userId)
                .orderByDesc(UserNotification::getCreatedAt)
                .last("LIMIT " + realLimit);
        if (unreadOnly) {
            qw.eq(UserNotification::getIsRead, 0);
        }
        return notificationMapper.selectList(qw).stream().map(this::toResponse).toList();
    }

    public UnreadCountResponse getUnreadCount(Long userId) {
        return new UnreadCountResponse(getUnreadCountRaw(userId));
    }

    public void markRead(Long userId, MarkNotificationReadRequest request) {
        boolean markAll = request == null || Boolean.TRUE.equals(request.getAll());
        if (markAll) {
            notificationMapper.update(
                    null,
                    new LambdaUpdateWrapper<UserNotification>()
                            .eq(UserNotification::getUserId, userId)
                            .eq(UserNotification::getIsRead, 0)
                            .set(UserNotification::getIsRead, 1)
                            .set(UserNotification::getReadAt, LocalDateTime.now())
            );
            return;
        }
        if (request.getConversationId() != null) {
            notificationMapper.update(
                    null,
                    new LambdaUpdateWrapper<UserNotification>()
                            .eq(UserNotification::getUserId, userId)
                            .eq(UserNotification::getConversationId, request.getConversationId())
                            .eq(UserNotification::getIsRead, 0)
                            .set(UserNotification::getIsRead, 1)
                            .set(UserNotification::getReadAt, LocalDateTime.now())
            );
            return;
        }
        if (request.getIds() != null && !request.getIds().isEmpty()) {
            notificationMapper.update(
                    null,
                    new LambdaUpdateWrapper<UserNotification>()
                            .eq(UserNotification::getUserId, userId)
                            .in(UserNotification::getId, request.getIds())
                            .eq(UserNotification::getIsRead, 0)
                            .set(UserNotification::getIsRead, 1)
                            .set(UserNotification::getReadAt, LocalDateTime.now())
            );
        }
    }

    public void upsertPushSubscription(Long userId, PushSubscriptionRequest request) {
        WebPushSubscription existing = subscriptionMapper.selectOne(
                new LambdaQueryWrapper<WebPushSubscription>()
                        .eq(WebPushSubscription::getEndpoint, request.getEndpoint())
                        .last("LIMIT 1")
        );
        if (existing == null) {
            WebPushSubscription row = new WebPushSubscription();
            row.setUserId(userId);
            row.setEndpoint(request.getEndpoint());
            row.setP256dh(request.getP256dh());
            row.setAuth(request.getAuth());
            row.setUserAgent(request.getUserAgent());
            row.setEnabled(1);
            subscriptionMapper.insert(row);
        } else {
            existing.setUserId(userId);
            existing.setP256dh(request.getP256dh());
            existing.setAuth(request.getAuth());
            existing.setUserAgent(request.getUserAgent());
            existing.setEnabled(1);
            subscriptionMapper.updateById(existing);
        }
    }

    public void disablePushSubscription(Long userId, String endpoint) {
        if (!StringUtils.hasText(endpoint)) {
            return;
        }
        subscriptionMapper.update(
                null,
                new LambdaUpdateWrapper<WebPushSubscription>()
                        .eq(WebPushSubscription::getUserId, userId)
                        .eq(WebPushSubscription::getEndpoint, endpoint)
                        .set(WebPushSubscription::getEnabled, 0)
        );
    }

    public List<WebPushSubscription> listEnabledSubscriptions(Long userId) {
        return subscriptionMapper.selectList(new LambdaQueryWrapper<WebPushSubscription>()
                .eq(WebPushSubscription::getUserId, userId)
                .eq(WebPushSubscription::getEnabled, 1));
    }

    public void disableSubscriptionByEndpoint(String endpoint) {
        if (!StringUtils.hasText(endpoint)) {
            return;
        }
        subscriptionMapper.update(
                null,
                new LambdaUpdateWrapper<WebPushSubscription>()
                        .eq(WebPushSubscription::getEndpoint, endpoint)
                        .set(WebPushSubscription::getEnabled, 0)
        );
    }

    public String getPushClickUrlPrefix() {
        return pushClickUrlPrefix;
    }

    public String getVapidPublicKey() {
        return vapidPublicKey;
    }

    public boolean isPushServerConfigured() {
        return pushEnabled && vapidPublicKey != null && !vapidPublicKey.isBlank();
    }

    public boolean hasActivePushSubscription(Long userId) {
        return hasAnySubscription(userId);
    }

    private boolean hasAnySubscription(Long userId) {
        Long count = subscriptionMapper.selectCount(new LambdaQueryWrapper<WebPushSubscription>()
                .eq(WebPushSubscription::getUserId, userId)
                .eq(WebPushSubscription::getEnabled, 1));
        return count != null && count > 0;
    }

    private Long getUnreadCountRaw(Long userId) {
        Long count = notificationMapper.selectCount(new LambdaQueryWrapper<UserNotification>()
                .eq(UserNotification::getUserId, userId)
                .eq(UserNotification::getIsRead, 0));
        return count == null ? 0L : count;
    }

    private NotificationResponse toResponse(UserNotification row) {
        return NotificationResponse.builder()
                .id(row.getId())
                .type(row.getType())
                .conversationId(row.getConversationId())
                .characterId(row.getCharacterId())
                .title(row.getTitle())
                .contentPreview(row.getContentPreview())
                .read(row.getIsRead() != null && row.getIsRead() == 1)
                .createdAt(row.getCreatedAt())
                .build();
    }

    private String trimPreview(String preview) {
        if (preview == null) {
            return "";
        }
        String t = preview.trim();
        if (t.length() <= 100) {
            return t;
        }
        return t.substring(0, 100) + "...";
    }
}
