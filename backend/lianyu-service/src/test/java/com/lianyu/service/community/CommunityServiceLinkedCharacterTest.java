package com.lianyu.service.community;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lianyu.common.exception.BusinessException;
import com.lianyu.dao.entity.Character;
import com.lianyu.dao.entity.CommunityLike;
import com.lianyu.dao.entity.CommunityPost;
import com.lianyu.dao.entity.User;
import java.time.LocalDateTime;
import com.lianyu.dao.mapper.CharacterMapper;
import com.lianyu.dao.mapper.CommunityCommentMapper;
import com.lianyu.dao.mapper.CommunityLikeMapper;
import com.lianyu.dao.mapper.CommunityPostMapper;
import com.lianyu.dao.mapper.UserMapper;
import com.lianyu.service.dto.CommunityFeedResponse;
import com.lianyu.service.dto.CommunityPostResponse;
import com.lianyu.service.dto.CreateCommunityPostRequest;
import com.lianyu.service.notification.NotificationService;
import com.lianyu.service.storage.FileStorageService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class CommunityServiceLinkedCharacterTest {

    @Mock private CommunityPostMapper communityPostMapper;
    @Mock private CommunityCommentMapper communityCommentMapper;
    @Mock private CommunityLikeMapper communityLikeMapper;
    @Mock private CharacterMapper characterMapper;
    @Mock private UserMapper userMapper;
    @Mock private FileStorageService fileStorageService;
    @Mock private RabbitTemplate rabbitTemplate;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOperations;
    @Mock private NotificationService notificationService;

    private CommunityService service;

    @BeforeEach
    void setUp() {
        service = new CommunityService(
                communityPostMapper,
                communityCommentMapper,
                communityLikeMapper,
                characterMapper,
                userMapper,
                fileStorageService,
                rabbitTemplate,
                redisTemplate,
                notificationService
        );
        ReflectionTestUtils.setField(service, "postRatePerMinute", 6);
    }

    @Test
    void createPost_rejectsCharacterNotOwnedByAuthor() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(any())).thenReturn(1L);

        CreateCommunityPostRequest request = new CreateCommunityPostRequest();
        request.setContent("分享日常");
        request.setLinkedCharacterId(99L);

        Character foreign = new Character();
        foreign.setId(99L);
        foreign.setOwnerUserId(2L);
        when(characterMapper.selectById(99L)).thenReturn(foreign);

        assertThrows(BusinessException.class, () -> service.createPost(1L, request));
    }

    @Test
    void createPost_persistsLinkedCharacterAndReturnsBadgeFields() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(any())).thenReturn(1L);

        CreateCommunityPostRequest request = new CreateCommunityPostRequest();
        request.setContent("和 TA 聊得很开心");
        request.setLinkedCharacterId(7L);

        Character owned = new Character();
        owned.setId(7L);
        owned.setOwnerUserId(1L);
        owned.setName("艾丽西亚");
        owned.setAvatarUrl("avatars/alice.png");
        when(characterMapper.selectById(7L)).thenReturn(owned);

        User author = new User();
        author.setId(1L);
        author.setNickname("小明");
        when(userMapper.selectById(1L)).thenReturn(author);
        when(fileStorageService.resolvePublicUrl(null)).thenReturn(null);
        when(fileStorageService.resolveSquareAvatarThumbPublicUrl("avatars/alice.png"))
                .thenReturn("/files/avatars/alice.png");

        when(communityPostMapper.insert(any(CommunityPost.class))).thenAnswer(invocation -> {
            CommunityPost post = invocation.getArgument(0);
            post.setId(100L);
            return 1;
        });

        CommunityPostResponse response = service.createPost(1L, request);

        ArgumentCaptor<CommunityPost> captor = ArgumentCaptor.forClass(CommunityPost.class);
        verify(communityPostMapper).insert(captor.capture());
        assertThat(captor.getValue().getLinkedCharacterId()).isEqualTo(7L);
        assertThat(response.getLinkedCharacterId()).isEqualTo(7L);
        assertThat(response.getLinkedCharacterName()).isEqualTo("艾丽西亚");
        assertThat(response.getLinkedCharacterAvatarUrl()).isEqualTo("/files/avatars/alice.png");
    }

    @Test
    void listFeed_mapsPublishedPostWithLikeAndNoLinkedCharacter() {
        CommunityPost post = new CommunityPost();
        post.setId(1L);
        post.setAuthorUserId(60L);
        post.setLinkedCharacterId(null);
        post.setContent("hello community");
        post.setImageUrls(null);
        post.setStatus(CommunityModerationService.STATUS_PUBLISHED);
        post.setLikeCount(2);
        post.setCommentCount(0);
        post.setCreatedAt(LocalDateTime.now());

        User author = new User();
        author.setId(60L);
        author.setNickname("作者");
        author.setAvatarUrl("avatars/a.png");

        CommunityLike like = new CommunityLike();
        like.setId(3L);
        like.setPostId(1L);
        like.setUserId(145L);

        when(communityPostMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(post));
        when(userMapper.selectBatchIds(any())).thenReturn(List.of(author));
        when(communityLikeMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(like));
        when(fileStorageService.resolvePublicUrl("avatars/a.png")).thenReturn("/files/avatars/a.png");

        CommunityFeedResponse feed = service.listFeed(145L, null, 20);

        assertThat(feed.getItems()).hasSize(1);
        assertThat(feed.getItems().get(0).getId()).isEqualTo(1L);
        assertThat(feed.getItems().get(0).isLikedByMe()).isTrue();
        assertThat(feed.getItems().get(0).getLinkedCharacterId()).isNull();
    }

    @Test
    void listUserPosts_filtersByCharacterOwnedByAuthor() {
        User author = new User();
        author.setId(5L);
        author.setNickname("作者");
        when(userMapper.selectById(5L)).thenReturn(author);

        Character owned = new Character();
        owned.setId(7L);
        owned.setOwnerUserId(5L);
        owned.setName("角色A");
        when(characterMapper.selectById(7L)).thenReturn(owned);

        CommunityPost post = new CommunityPost();
        post.setId(11L);
        post.setAuthorUserId(5L);
        post.setLinkedCharacterId(7L);
        post.setContent("筛选命中");
        post.setStatus(CommunityModerationService.STATUS_PUBLISHED);
        post.setLikeCount(0);
        post.setCommentCount(0);
        when(communityPostMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(post));
        when(userMapper.selectBatchIds(any())).thenReturn(List.of(author));
        when(characterMapper.selectBatchIds(any())).thenReturn(List.of(owned));
        when(communityLikeMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

        CommunityFeedResponse feed = service.listUserPosts(9L, 5L, null, 5, 7L);

        assertThat(feed.getItems()).hasSize(1);
        assertThat(feed.getItems().get(0).getLinkedCharacterId()).isEqualTo(7L);
        assertThat(feed.getItems().get(0).getLinkedCharacterName()).isEqualTo("角色A");
    }
}
