package com.lianyu.service.community;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lianyu.dao.entity.CommunityPost;
import com.lianyu.dao.mapper.CommunityPostMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CommunityModerationServiceTest {

    @Mock private CommunityPostMapper communityPostMapper;
    @InjectMocks private CommunityModerationService service;

    @Test
    void finalizePendingPost_publishesWhenRulesPass() {
        CommunityPost post = new CommunityPost();
        post.setId(9L);
        post.setStatus(CommunityModerationService.STATUS_PENDING);
        post.setContent("哈喽");

        when(communityPostMapper.selectById(9L)).thenReturn(post);
        when(communityPostMapper.selectOne(any())).thenReturn(post);

        service.finalizePendingPost(9L);

        ArgumentCaptor<CommunityPost> captor = ArgumentCaptor.forClass(CommunityPost.class);
        verify(communityPostMapper).updateById(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(CommunityModerationService.STATUS_PUBLISHED);
        assertThat(captor.getValue().getRejectReason()).isNull();
    }

    @Test
    void finalizePendingPost_rejectsBlockedWords() {
        CommunityPost post = new CommunityPost();
        post.setId(10L);
        post.setStatus(CommunityModerationService.STATUS_PENDING);
        post.setContent("涉及赌博");

        when(communityPostMapper.selectById(10L)).thenReturn(post);
        when(communityPostMapper.selectOne(any())).thenReturn(post);

        service.finalizePendingPost(10L);

        ArgumentCaptor<CommunityPost> captor = ArgumentCaptor.forClass(CommunityPost.class);
        verify(communityPostMapper).updateById(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(CommunityModerationService.STATUS_REJECTED);
        assertThat(captor.getValue().getRejectReason()).isNotBlank();
    }

    @Test
    void finalizePendingPost_skipsNonPending() {
        CommunityPost post = new CommunityPost();
        post.setId(11L);
        post.setStatus(CommunityModerationService.STATUS_PUBLISHED);
        when(communityPostMapper.selectById(11L)).thenReturn(post);

        service.finalizePendingPost(11L);

        verify(communityPostMapper, never()).updateById(org.mockito.ArgumentMatchers.<CommunityPost>any());
    }
}
