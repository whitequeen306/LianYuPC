package com.lianyu.service.moments;

import com.lianyu.dao.mapper.MomentsPostMapper;
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
 * 补偿调度：修复「路人评论已标记完成但实际没有任何其他角色评论」的动态。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MomentsCommentReconciliationScheduler {

    private final MomentsPostMapper momentsPostMapper;
    private final MomentsCommentOrchestrator momentsCommentOrchestrator;

    @Value("${lianyu.moments.comments.reconcile-enabled:true}")
    private boolean reconcileEnabled;

    @Value("${lianyu.moments.comments.reconcile-min-age-minutes:4}")
    private int reconcileMinAgeMinutes;

    @Value("${lianyu.moments.comments.reconcile-batch-size:20}")
    private int reconcileBatchSize;

    @EventListener(ApplicationReadyEvent.class)
    public void reconcileOnStartup() {
        reconcileStuckPosts();
    }

    @Scheduled(fixedDelayString = "${lianyu.moments.comments.reconcile-interval-ms:300000}")
    public void reconcileStuckPosts() {
        if (!reconcileEnabled) {
            return;
        }
        LocalDateTime before = LocalDateTime.now().minusMinutes(Math.max(1, reconcileMinAgeMinutes));
        List<Long> postIds = momentsPostMapper.selectPostIdsNeedingPeerComments(
                before, Math.max(1, reconcileBatchSize));
        if (postIds.isEmpty()) {
            return;
        }
        log.info("Moments comment reconcile: retrying {} posts without peer comments", postIds.size());
        for (Long postId : postIds) {
            try {
                momentsCommentOrchestrator.reconcilePeerComments(postId);
            } catch (Exception e) {
                log.warn("Moments comment reconcile failed: postId={}, reason={}", postId, e.getMessage());
            }
        }
    }
}
