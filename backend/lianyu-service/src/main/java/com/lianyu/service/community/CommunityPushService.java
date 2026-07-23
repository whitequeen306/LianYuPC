package com.lianyu.service.community;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lianyu.dao.entity.CommunityPost;
import com.lianyu.dao.entity.User;
import com.lianyu.dao.mapper.CommunityPostMapper;
import com.lianyu.dao.mapper.UserMapper;
import com.lianyu.service.notification.NotificationService;
import com.lianyu.service.user.UserSettingsResolver;
import java.time.Duration;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Community feed toast push: opt-out via settings (default ON), last-seen cursor in Redis.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CommunityPushService {

    private static final String LAST_SEEN_KEY_PREFIX = "community:last-seen-post:";
    private static final Duration LAST_SEEN_TTL = Duration.ofDays(90);

    private final CommunityPostMapper communityPostMapper;
    private final UserMapper userMapper;
    private final StringRedisTemplate redisTemplate;
    private final NotificationService notificationService;

    public void broadcastNewPost(Long postId, Long authorUserId) {
        if (postId == null || authorUserId == null) {
            return;
        }
        CommunityPost post = communityPostMapper.selectById(postId);
        if (post == null || !CommunityModerationService.STATUS_PUBLISHED.equals(post.getStatus())) {
            return;
        }
        User author = userMapper.selectById(authorUserId);
        String authorName = author != null && author.getNickname() != null && !author.getNickname().isBlank()
                ? author.getNickname().trim()
                : "有人";
        String authorAvatar = notificationService.resolveUserAvatarUrl(author);
        String preview = buildPreview(post);

        List<User> users = userMapper.selectList(new LambdaQueryWrapper<User>()
                .ne(User::getId, authorUserId));
        int sent = 0;
        for (User user : users) {
            if (user.getId() == null) {
                continue;
            }
            if (!UserSettingsResolver.communityPushEnabled(user.getSettingsJson())) {
                continue;
            }
            try {
                notificationService.notifyCommunityPostNew(
                        user.getId(), postId, authorName, preview, authorAvatar);
                markSeenAtLeast(user.getId(), postId);
                sent += 1;
            } catch (Exception e) {
                log.warn("Community post notify failed: userId={}, postId={}, reason={}",
                        user.getId(), postId, e.getMessage());
            }
        }
        log.info("Community post broadcast: postId={}, authorUserId={}, notified={}",
                postId, authorUserId, sent);
    }

    /**
     * On user online: push the latest unseen published post from others (at most one).
     */
    public void catchUpOnOnline(Long userId) {
        if (userId == null) {
            return;
        }
        User user = userMapper.selectById(userId);
        if (user == null || !UserSettingsResolver.communityPushEnabled(user.getSettingsJson())) {
            return;
        }
        long lastSeen = getLastSeenPostId(userId);
        CommunityPost latest = communityPostMapper.selectOne(new LambdaQueryWrapper<CommunityPost>()
                .eq(CommunityPost::getStatus, CommunityModerationService.STATUS_PUBLISHED)
                .ne(CommunityPost::getAuthorUserId, userId)
                .gt(CommunityPost::getId, lastSeen)
                .orderByDesc(CommunityPost::getId)
                .last("LIMIT 1"));
        if (latest == null) {
            return;
        }
        User author = userMapper.selectById(latest.getAuthorUserId());
        String authorName = author != null && author.getNickname() != null && !author.getNickname().isBlank()
                ? author.getNickname().trim()
                : "有人";
        notificationService.notifyCommunityPostNew(
                userId,
                latest.getId(),
                authorName,
                buildPreview(latest),
                notificationService.resolveUserAvatarUrl(author));
        markSeenAtLeast(userId, latest.getId());
        log.info("Community push catch-up: userId={}, postId={}", userId, latest.getId());
    }

    public void markFeedSeen(Long userId, Long maxPostId) {
        if (userId == null || maxPostId == null || maxPostId <= 0) {
            return;
        }
        markSeenAtLeast(userId, maxPostId);
    }

    public long getLastSeenPostId(Long userId) {
        if (userId == null) {
            return 0L;
        }
        String raw = redisTemplate.opsForValue().get(LAST_SEEN_KEY_PREFIX + userId);
        if (raw == null || raw.isBlank()) {
            return 0L;
        }
        try {
            return Math.max(0L, Long.parseLong(raw.trim()));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private void markSeenAtLeast(Long userId, Long postId) {
        if (userId == null || postId == null || postId <= 0) {
            return;
        }
        long current = getLastSeenPostId(userId);
        if (postId <= current) {
            return;
        }
        redisTemplate.opsForValue().set(
                LAST_SEEN_KEY_PREFIX + userId,
                String.valueOf(postId),
                LAST_SEEN_TTL);
    }

    private static String buildPreview(CommunityPost post) {
        String content = post.getContent() != null ? post.getContent().trim() : "";
        if (!content.isBlank()) {
            return content;
        }
        if (post.getImageUrls() != null && !post.getImageUrls().isEmpty()) {
            return "[图片]";
        }
        return "发布了新动态";
    }
}
