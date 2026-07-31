package com.lianyu.service.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.lianyu.dao.entity.Character;
import com.lianyu.dao.entity.UserNotification;
import com.lianyu.dao.mapper.CharacterMapper;
import com.lianyu.dao.mapper.UserNotificationMapper;
import com.lianyu.dao.mapper.WebPushSubscriptionMapper;
import com.lianyu.service.dto.UnreadCountResponse;
import com.lianyu.service.storage.FileStorageService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    @Mock private UserNotificationMapper notificationMapper;
    @Mock private WebPushSubscriptionMapper subscriptionMapper;
    @Mock private CharacterMapper characterMapper;
    @Mock private FileStorageService fileStorageService;
    @Mock private SimpMessagingTemplate messagingTemplate;
    @Mock private RabbitTemplate rabbitTemplate;

    @InjectMocks private NotificationService notificationService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(notificationService, "pushEnabled", false);
    }

    @Test
    void notifyAssistantMessage_skipsWhenCharacterMissing() {
        when(characterMapper.selectById(9L)).thenReturn(null);

        var result = notificationService.notifyAssistantMessage(
                1L, 2L, 9L, "已删角色", "还在吗", "PROACTIVE_MESSAGE");

        assertNull(result);
        verify(notificationMapper, never()).insert(any(UserNotification.class));
        verify(messagingTemplate, never()).convertAndSendToUser(any(), any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void deleteForCharacter_deletesAndPushesUnread() {
        when(notificationMapper.delete(any(Wrapper.class))).thenReturn(3);
        when(notificationMapper.selectCount(any(Wrapper.class))).thenReturn(1L);

        int deleted = notificationService.deleteForCharacter(1L, 9L, List.of(11L, 12L));

        assertEquals(3, deleted);
        verify(notificationMapper).delete(any(Wrapper.class));
        ArgumentCaptor<UnreadCountResponse> unreadCaptor = ArgumentCaptor.forClass(UnreadCountResponse.class);
        verify(messagingTemplate).convertAndSendToUser(
                eq("1"), eq("/queue/notification-unread"), unreadCaptor.capture());
        assertEquals(1L, unreadCaptor.getValue().getUnreadCount());
    }

    @Test
    @SuppressWarnings("unchecked")
    void deleteForCharacter_noopWhenNothingDeleted() {
        when(notificationMapper.delete(any(Wrapper.class))).thenReturn(0);

        int deleted = notificationService.deleteForCharacter(1L, 9L, List.of());

        assertEquals(0, deleted);
        verify(messagingTemplate, never()).convertAndSendToUser(any(), any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void deleteForConversation_deletesScopedRows() {
        when(notificationMapper.delete(any(Wrapper.class))).thenReturn(2);
        when(notificationMapper.selectCount(any(Wrapper.class))).thenReturn(0L);

        int deleted = notificationService.deleteForConversation(1L, 42L);

        assertEquals(2, deleted);
        verify(messagingTemplate).convertAndSendToUser(
                eq("1"), eq("/queue/notification-unread"), any(UnreadCountResponse.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void notifyAssistantMessage_insertsWhenCharacterExists() {
        Character character = new Character();
        character.setId(9L);
        character.setAvatarUrl("avatars/a.png");
        when(characterMapper.selectById(9L)).thenReturn(character);
        when(fileStorageService.resolveSquareAvatarThumbPublicUrl("avatars/a.png"))
                .thenReturn("https://cdn/a.png");
        when(notificationMapper.insert(any(UserNotification.class))).thenAnswer(invocation -> {
            UserNotification row = invocation.getArgument(0);
            row.setId(100L);
            return 1;
        });
        when(notificationMapper.selectCount(any(Wrapper.class))).thenReturn(1L);

        var result = notificationService.notifyProactiveMessage(
                1L, 2L, 9L, "小雪", "在吗");

        org.junit.jupiter.api.Assertions.assertNotNull(result);
        assertEquals(100L, result.getId());
        verify(notificationMapper).insert(any(UserNotification.class));
        verify(messagingTemplate).convertAndSendToUser(eq("1"), eq("/queue/notifications"), any());
    }
}
