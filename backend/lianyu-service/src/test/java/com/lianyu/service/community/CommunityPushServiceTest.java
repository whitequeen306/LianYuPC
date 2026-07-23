package com.lianyu.service.community;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lianyu.dao.entity.CommunityPost;
import com.lianyu.dao.entity.User;
import com.lianyu.dao.mapper.CommunityPostMapper;
import com.lianyu.dao.mapper.UserMapper;
import com.lianyu.service.notification.NotificationService;
import com.lianyu.service.user.UserSettingsResolver;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@ExtendWith(MockitoExtension.class)
class CommunityPushServiceTest {

    @Mock private CommunityPostMapper communityPostMapper;
    @Mock private UserMapper userMapper;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOperations;
    @Mock private NotificationService notificationService;
    @InjectMocks private CommunityPushService service;

    @Test
    void catchUpOnOnline_skipsWhenPushDisabled() {
        User user = new User();
        user.setId(1L);
        user.setSettingsJson(UserSettingsResolver.withCommunityPushEnabled(null, false));
        when(userMapper.selectById(1L)).thenReturn(user);

        service.catchUpOnOnline(1L);

        verify(communityPostMapper, never()).selectOne(any());
        verify(notificationService, never()).notifyCommunityPostNew(anyLong(), anyLong(), anyString(), anyString());
    }

    @Test
    void catchUpOnOnline_notifiesLatestUnseenPost() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("community:last-seen-post:1")).thenReturn("5");

        User user = new User();
        user.setId(1L);
        user.setSettingsJson(null);
        when(userMapper.selectById(1L)).thenReturn(user);

        CommunityPost post = new CommunityPost();
        post.setId(12L);
        post.setAuthorUserId(2L);
        post.setContent("哈喽");
        post.setStatus(CommunityModerationService.STATUS_PUBLISHED);
        when(communityPostMapper.selectOne(any())).thenReturn(post);

        User author = new User();
        author.setId(2L);
        author.setNickname("小明");
        when(userMapper.selectById(2L)).thenReturn(author);

        service.catchUpOnOnline(1L);

        verify(notificationService).notifyCommunityPostNew(eq(1L), eq(12L), eq("小明"), eq("哈喽"));
        verify(valueOperations).set(eq("community:last-seen-post:1"), eq("12"), any());
    }

    @Test
    void broadcastNewPost_skipsOptedOutUsers() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn(null);

        CommunityPost post = new CommunityPost();
        post.setId(9L);
        post.setAuthorUserId(1L);
        post.setContent("新动态");
        post.setStatus(CommunityModerationService.STATUS_PUBLISHED);
        when(communityPostMapper.selectById(9L)).thenReturn(post);

        User author = new User();
        author.setId(1L);
        author.setNickname("作者");
        when(userMapper.selectById(1L)).thenReturn(author);

        User optedIn = new User();
        optedIn.setId(2L);
        optedIn.setSettingsJson(null);
        User optedOut = new User();
        optedOut.setId(3L);
        optedOut.setSettingsJson(UserSettingsResolver.withCommunityPushEnabled(null, false));
        when(userMapper.selectList(any())).thenReturn(List.of(optedIn, optedOut));

        service.broadcastNewPost(9L, 1L);

        verify(notificationService).notifyCommunityPostNew(eq(2L), eq(9L), eq("作者"), eq("新动态"));
        verify(notificationService, never()).notifyCommunityPostNew(eq(3L), anyLong(), anyString(), anyString());
    }
}
