package com.lianyu.service.community;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lianyu.dao.entity.CommunityPost;
import com.lianyu.dao.mapper.CommunityPostMapper;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Publishes legacy {@code pending} posts that were stuck under the old async AI moderation path.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CommunityModerationReconcileScheduler {

    private final CommunityPostMapper communityPostMapper;
    private final CommunityModerationService communityModerationService;

    @Value("${lianyu.community.reconcile-enabled:true}")
    private boolean reconcileEnabled;

    @Value("${lianyu.community.reconcile-min-age-minutes:1}")
    private int reconcileMinAgeMinutes;

    @Value("${lianyu.community.reconcile-batch-size:50}")
    private int reconcileBatchSize;

    @EventListener(ApplicationReadyEvent.class)
    public void reconcileOnStartup() {
        reconcileStuckPendingPosts();
    }

    @Scheduled(fixedDelayString = "${lianyu.community.reconcile-interval-ms:120000}")
    public void reconcileStuckPendingPosts() {
        if (!reconcileEnabled) {
            return;
        }
        LocalDateTime before = LocalDateTime.now().minusMinutes(Math.max(0, reconcileMinAgeMinutes));
        List<CommunityPost> rows = communityPostMapper.selectList(new LambdaQueryWrapper<CommunityPost>()
                .eq(CommunityPost::getStatus, CommunityModerationService.STATUS_PENDING)
                .lt(CommunityPost::getCreatedAt, before)
                .orderByAsc(CommunityPost::getId)
                .last("LIMIT " + Math.max(1, reconcileBatchSize)));
        if (rows.isEmpty()) {
            return;
        }
        log.info("Community moderation reconcile: finalizing {} pending posts", rows.size());
        for (CommunityPost row : rows) {
            try {
                communityModerationService.finalizePendingPost(row.getId());
            } catch (Exception e) {
                log.warn("Community moderation reconcile failed: postId={}, reason={}",
                        row.getId(), e.getMessage());
            }
        }
    }
}
