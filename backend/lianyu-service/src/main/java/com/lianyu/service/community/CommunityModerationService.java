package com.lianyu.service.community;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lianyu.dao.entity.CommunityPost;
import com.lianyu.dao.mapper.CommunityPostMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class CommunityModerationService {

    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_PUBLISHED = "published";
    public static final String STATUS_REJECTED = "rejected";
    public static final String STATUS_DELETED = "deleted";

    private final CommunityPostMapper communityPostMapper;

    /**
     * Legacy queue/backlog handler: finalize a pending post with sync rules only (no AI).
     */
    @Transactional
    public void finalizePendingPost(Long postId) {
        CommunityPost post = communityPostMapper.selectById(postId);
        if (post == null || !STATUS_PENDING.equals(post.getStatus())) {
            return;
        }

        boolean allow = CommunityContentRules.passesSecondaryRules(post.getContent());
        String rejectReason = allow ? null : "未通过内容规则审核";

        CommunityPost fresh = communityPostMapper.selectOne(new LambdaQueryWrapper<CommunityPost>()
                .eq(CommunityPost::getId, postId)
                .eq(CommunityPost::getStatus, STATUS_PENDING)
                .last("LIMIT 1"));
        if (fresh == null) {
            return;
        }
        fresh.setStatus(allow ? STATUS_PUBLISHED : STATUS_REJECTED);
        fresh.setRejectReason(rejectReason);
        communityPostMapper.updateById(fresh);
        log.info("Community post finalized: postId={}, status={}", postId, fresh.getStatus());
    }

    /** @deprecated use {@link #finalizePendingPost}; kept for old queue consumers. */
    @Deprecated
    @Transactional
    public void moderatePost(Long postId) {
        finalizePendingPost(postId);
    }
}
