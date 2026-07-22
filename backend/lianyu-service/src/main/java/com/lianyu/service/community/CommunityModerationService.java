package com.lianyu.service.community;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lianyu.dao.entity.CommunityPost;
import com.lianyu.dao.mapper.CommunityPostMapper;
import com.lianyu.service.ai.AiChatService;
import java.util.Optional;
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
    private final AiChatService aiChatService;

    @Transactional
    public void moderatePost(Long postId) {
        CommunityPost post = communityPostMapper.selectById(postId);
        if (post == null) {
            return;
        }
        if (!STATUS_PENDING.equals(post.getStatus())) {
            return;
        }

        Optional<Boolean> ai = aiChatService.moderateCommunityContent(post.getAuthorUserId(), post.getContent());
        boolean allow;
        String rejectReason = null;
        if (ai.isPresent()) {
            allow = ai.get();
            if (!allow) {
                rejectReason = "未通过内容审核";
            }
        } else {
            allow = CommunityContentRules.passesSecondaryRules(post.getContent());
            if (!allow) {
                rejectReason = "未通过内容规则审核";
            }
            log.info("Community moderation fallback to rules: postId={}, allow={}", postId, allow);
        }

        // Re-check still pending (avoid racing with author delete)
        CommunityPost fresh = communityPostMapper.selectOne(new LambdaQueryWrapper<CommunityPost>()
                .eq(CommunityPost::getId, postId)
                .eq(CommunityPost::getStatus, STATUS_PENDING)
                .last("LIMIT 1"));
        if (fresh == null) {
            return;
        }
        fresh.setStatus(allow ? STATUS_PUBLISHED : STATUS_REJECTED);
        fresh.setRejectReason(allow ? null : rejectReason);
        communityPostMapper.updateById(fresh);
        log.info("Community post moderated: postId={}, status={}", postId, fresh.getStatus());
    }
}
