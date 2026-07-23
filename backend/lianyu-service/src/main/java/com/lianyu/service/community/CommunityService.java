package com.lianyu.service.community;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.lianyu.common.base.ErrorCode;
import com.lianyu.common.exception.BusinessException;
import com.lianyu.dao.entity.CommunityComment;
import com.lianyu.dao.entity.CommunityLike;
import com.lianyu.dao.entity.CommunityPost;
import com.lianyu.dao.entity.Character;
import com.lianyu.dao.entity.User;
import com.lianyu.dao.mapper.CommunityCommentMapper;
import com.lianyu.dao.mapper.CommunityLikeMapper;
import com.lianyu.dao.mapper.CommunityPostMapper;
import com.lianyu.dao.mapper.CharacterMapper;
import com.lianyu.dao.mapper.UserMapper;
import com.lianyu.service.dto.*;
import com.lianyu.service.notification.NotificationService;
import com.lianyu.service.storage.FileStorageService;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Slf4j
@Service
@RequiredArgsConstructor
public class CommunityService {

    private static final String EXCHANGE = "lianyu.exchange";
    private static final String RK_COMMUNITY_MODERATION = "community.moderation";
    private static final String RATE_KEY_PREFIX = "community:post-rate:";

    private final CommunityPostMapper communityPostMapper;
    private final CommunityCommentMapper communityCommentMapper;
    private final CommunityLikeMapper communityLikeMapper;
    private final CharacterMapper characterMapper;
    private final UserMapper userMapper;
    private final FileStorageService fileStorageService;
    private final RabbitTemplate rabbitTemplate;
    private final StringRedisTemplate redisTemplate;
    private final NotificationService notificationService;

    @Value("${lianyu.community.post-rate-per-minute:6}")
    private int postRatePerMinute;

    public String uploadImage(MultipartFile file) {
        return fileStorageService.uploadCommunityImage(file);
    }

    @Transactional
    public CommunityPostResponse createPost(Long userId, CreateCommunityPostRequest request) {
        assertPostRate(userId);
        String content = CommunityContentRules.sanitizePostContent(request.getContent());
        List<String> images = normalizeImageUrls(request.getImageUrls());
        CommunityContentRules.assertPostAllowed(content, images);
        Character linkedCharacter = resolveLinkedCharacter(userId, request.getLinkedCharacterId());

        CommunityPost post = new CommunityPost();
        post.setAuthorUserId(userId);
        post.setLinkedCharacterId(linkedCharacter != null ? linkedCharacter.getId() : null);
        post.setContent(content.isBlank() ? "" : content);
        post.setImageUrls(images.isEmpty() ? null : images);
        post.setStatus(CommunityModerationService.STATUS_PENDING);
        post.setLikeCount(0);
        post.setCommentCount(0);
        communityPostMapper.insert(post);

        rabbitTemplate.convertAndSend(EXCHANGE, RK_COMMUNITY_MODERATION,
                new CommunityModerationTask(post.getId(), userId));

        return toPostResponse(post, loadUser(userId), linkedCharacter, false, true);
    }

    @Transactional
    public void deletePost(Long userId, Long postId) {
        CommunityPost post = requirePost(postId);
        if (!Objects.equals(post.getAuthorUserId(), userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        if (CommunityModerationService.STATUS_DELETED.equals(post.getStatus())) {
            return;
        }
        post.setStatus(CommunityModerationService.STATUS_DELETED);
        communityPostMapper.updateById(post);
    }

    public CommunityFeedResponse listFeed(Long viewerId, Long cursor, int limit) {
        int pageSize = clampLimit(limit);
        LambdaQueryWrapper<CommunityPost> qw = new LambdaQueryWrapper<CommunityPost>()
                .eq(CommunityPost::getStatus, CommunityModerationService.STATUS_PUBLISHED)
                .orderByDesc(CommunityPost::getId)
                .last("LIMIT " + (pageSize + 1));
        if (cursor != null && cursor > 0) {
            qw.lt(CommunityPost::getId, cursor);
        }
        List<CommunityPost> rows = communityPostMapper.selectList(qw);
        return toFeed(viewerId, rows, pageSize, false);
    }

    public CommunityFeedResponse listUserPosts(Long viewerId, Long authorUserId, Long cursor, int limit) {
        return listUserPosts(viewerId, authorUserId, cursor, limit, null);
    }

    public CommunityFeedResponse listUserPosts(
            Long viewerId, Long authorUserId, Long cursor, int limit, Long characterId) {
        if (userMapper.selectById(authorUserId) == null) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }
        int pageSize = clampLimit(limit);
        boolean isSelf = Objects.equals(viewerId, authorUserId);
        LambdaQueryWrapper<CommunityPost> qw = new LambdaQueryWrapper<CommunityPost>()
                .eq(CommunityPost::getAuthorUserId, authorUserId)
                .orderByDesc(CommunityPost::getId)
                .last("LIMIT " + (pageSize + 1));
        if (characterId != null) {
            assertCharacterOwnedBy(authorUserId, characterId);
            qw.eq(CommunityPost::getLinkedCharacterId, characterId);
        }
        if (isSelf) {
            qw.in(CommunityPost::getStatus,
                    CommunityModerationService.STATUS_PUBLISHED,
                    CommunityModerationService.STATUS_PENDING,
                    CommunityModerationService.STATUS_REJECTED);
        } else {
            qw.eq(CommunityPost::getStatus, CommunityModerationService.STATUS_PUBLISHED);
        }
        if (cursor != null && cursor > 0) {
            qw.lt(CommunityPost::getId, cursor);
        }
        List<CommunityPost> rows = communityPostMapper.selectList(qw);
        return toFeed(viewerId, rows, pageSize, isSelf);
    }

    @Transactional
    public CommunityLikeResponse toggleLike(Long userId, Long postId) {
        CommunityPost post = requireVisiblePost(postId, userId);
        CommunityLike existing = communityLikeMapper.selectOne(new LambdaQueryWrapper<CommunityLike>()
                .eq(CommunityLike::getPostId, postId)
                .eq(CommunityLike::getUserId, userId)
                .last("LIMIT 1"));
        boolean liked;
        if (existing != null) {
            communityLikeMapper.deleteById(existing.getId());
            bumpLikeCount(postId, -1);
            liked = false;
        } else {
            CommunityLike like = new CommunityLike();
            like.setPostId(postId);
            like.setUserId(userId);
            communityLikeMapper.insert(like);
            bumpLikeCount(postId, 1);
            liked = true;
            if (!Objects.equals(post.getAuthorUserId(), userId)) {
                User actor = loadUser(userId);
                String name = actor.getNickname() != null ? actor.getNickname() : "有人";
                notificationService.notifyCommunityLike(
                        post.getAuthorUserId(), postId, name, trimPreview(post.getContent()));
            }
        }
        CommunityPost fresh = communityPostMapper.selectById(postId);
        int count = fresh != null && fresh.getLikeCount() != null ? fresh.getLikeCount() : 0;
        return CommunityLikeResponse.builder().liked(liked).likeCount(Math.max(0, count)).build();
    }

    public CommunityCommentListResponse listComments(Long viewerId, Long postId, Long cursor, int limit) {
        requireVisiblePost(postId, viewerId);
        int pageSize = clampLimit(limit);
        LambdaQueryWrapper<CommunityComment> qw = new LambdaQueryWrapper<CommunityComment>()
                .eq(CommunityComment::getPostId, postId)
                .eq(CommunityComment::getStatus, CommunityModerationService.STATUS_PUBLISHED)
                .orderByAsc(CommunityComment::getId)
                .last("LIMIT " + (pageSize + 1));
        if (cursor != null && cursor > 0) {
            qw.gt(CommunityComment::getId, cursor);
        }
        List<CommunityComment> rows = communityCommentMapper.selectList(qw);
        boolean hasMore = rows.size() > pageSize;
        if (hasMore) {
            rows = rows.subList(0, pageSize);
        }
        Map<Long, User> authors = loadUsers(rows.stream().map(CommunityComment::getAuthorUserId).toList());
        List<CommunityCommentResponse> items = rows.stream()
                .map(c -> toCommentResponse(c, authors.get(c.getAuthorUserId())))
                .toList();
        Long next = items.isEmpty() ? null : items.get(items.size() - 1).getId();
        return CommunityCommentListResponse.builder()
                .items(items)
                .nextCursor(hasMore ? next : null)
                .hasMore(hasMore)
                .build();
    }

    @Transactional
    public CommunityCommentResponse addComment(Long userId, Long postId, CreateCommunityCommentRequest request) {
        CommunityPost post = requireVisiblePost(postId, userId);
        String content = CommunityContentRules.sanitizeCommentContent(request.getContent());
        CommunityContentRules.assertCommentAllowed(content);

        CommunityComment comment = new CommunityComment();
        comment.setPostId(postId);
        comment.setAuthorUserId(userId);
        comment.setContent(content);
        comment.setStatus(CommunityModerationService.STATUS_PUBLISHED);
        communityCommentMapper.insert(comment);

        communityPostMapper.update(null, new LambdaUpdateWrapper<CommunityPost>()
                .eq(CommunityPost::getId, postId)
                .setSql("comment_count = comment_count + 1"));

        if (!Objects.equals(post.getAuthorUserId(), userId)) {
            User actor = loadUser(userId);
            String name = actor.getNickname() != null ? actor.getNickname() : "有人";
            notificationService.notifyCommunityComment(
                    post.getAuthorUserId(), postId, name, content);
        }
        return toCommentResponse(comment, loadUser(userId));
    }

    private CommunityFeedResponse toFeed(Long viewerId, List<CommunityPost> rows, int pageSize, boolean includeStatus) {
        boolean hasMore = rows.size() > pageSize;
        if (hasMore) {
            rows = new ArrayList<>(rows.subList(0, pageSize));
        }
        Map<Long, User> authors = loadUsers(rows.stream().map(CommunityPost::getAuthorUserId).toList());
        Map<Long, Character> linkedCharacters = loadCharacters(
                rows.stream().map(CommunityPost::getLinkedCharacterId).toList());
        Set<Long> likedIds = loadLikedPostIds(viewerId, rows.stream().map(CommunityPost::getId).toList());
        List<CommunityPostResponse> items = rows.stream()
                .map(p -> toPostResponse(
                        p,
                        authors.get(p.getAuthorUserId()),
                        linkedCharacters.get(p.getLinkedCharacterId()),
                        likedIds.contains(p.getId()),
                        includeStatus))
                .toList();
        Long next = items.isEmpty() ? null : items.get(items.size() - 1).getId();
        return CommunityFeedResponse.builder()
                .items(items)
                .nextCursor(hasMore ? next : null)
                .hasMore(hasMore)
                .build();
    }

    private CommunityPostResponse toPostResponse(
            CommunityPost post, User author, Character linkedCharacter, boolean likedByMe, boolean includeStatus) {
        List<String> images = post.getImageUrls() == null ? List.of() : post.getImageUrls().stream()
                .map(fileStorageService::resolvePublicUrl)
                .filter(Objects::nonNull)
                .toList();
        String status = includeStatus ? post.getStatus() : null;
        String reject = includeStatus ? post.getRejectReason() : null;
        Long linkedId = post.getLinkedCharacterId();
        String linkedName = linkedCharacter != null ? linkedCharacter.getName() : null;
        String linkedAvatar = linkedCharacter != null
                ? fileStorageService.resolvePublicUrl(linkedCharacter.getAvatarUrl())
                : null;
        return CommunityPostResponse.builder()
                .id(post.getId())
                .authorUserId(post.getAuthorUserId())
                .nickname(author != null ? author.getNickname() : "用户")
                .avatarUrl(author != null ? fileStorageService.resolvePublicUrl(author.getAvatarUrl()) : null)
                .content(post.getContent())
                .imageUrls(images)
                .linkedCharacterId(linkedId)
                .linkedCharacterName(linkedName)
                .linkedCharacterAvatarUrl(linkedAvatar)
                .status(status)
                .rejectReason(reject)
                .likeCount(post.getLikeCount() == null ? 0 : Math.max(0, post.getLikeCount()))
                .commentCount(post.getCommentCount() == null ? 0 : Math.max(0, post.getCommentCount()))
                .likedByMe(likedByMe)
                .createdAt(post.getCreatedAt())
                .build();
    }

    private CommunityCommentResponse toCommentResponse(CommunityComment comment, User author) {
        return CommunityCommentResponse.builder()
                .id(comment.getId())
                .postId(comment.getPostId())
                .authorUserId(comment.getAuthorUserId())
                .nickname(author != null ? author.getNickname() : "用户")
                .avatarUrl(author != null ? fileStorageService.resolvePublicUrl(author.getAvatarUrl()) : null)
                .content(comment.getContent())
                .status(comment.getStatus())
                .createdAt(comment.getCreatedAt())
                .build();
    }

    private List<String> normalizeImageUrls(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String item : raw) {
            if (item == null || item.isBlank()) {
                continue;
            }
            String key = FileStorageService.extractObjectKey(item.trim());
            if (key == null || !key.startsWith("community-images/")) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "图片地址无效，请重新上传");
            }
            out.add(FileStorageService.toPublicUrl(key));
            if (out.size() > 9) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "最多上传 9 张图片");
            }
        }
        return out;
    }

    private void assertPostRate(Long userId) {
        String key = RATE_KEY_PREFIX + userId;
        Long count = redisTemplate.opsForValue().increment(key);
        if (count != null && count == 1L) {
            redisTemplate.expire(key, Duration.ofMinutes(1));
        }
        if (count != null && count > postRatePerMinute) {
            throw new BusinessException(ErrorCode.AI_RATE_LIMITED, "发帖太频繁，请稍后再试");
        }
    }

    private CommunityPost requirePost(Long postId) {
        CommunityPost post = communityPostMapper.selectById(postId);
        if (post == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "动态不存在");
        }
        return post;
    }

    private CommunityPost requireVisiblePost(Long postId, Long viewerId) {
        CommunityPost post = requirePost(postId);
        if (CommunityModerationService.STATUS_DELETED.equals(post.getStatus())) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "动态不存在");
        }
        if (CommunityModerationService.STATUS_PUBLISHED.equals(post.getStatus())) {
            return post;
        }
        if (Objects.equals(post.getAuthorUserId(), viewerId)) {
            return post;
        }
        throw new BusinessException(ErrorCode.NOT_FOUND, "动态不存在");
    }

    private void bumpLikeCount(Long postId, int delta) {
        String sql = delta >= 0
                ? "like_count = like_count + " + delta
                : "like_count = GREATEST(like_count - " + (-delta) + ", 0)";
        communityPostMapper.update(null, new LambdaUpdateWrapper<CommunityPost>()
                .eq(CommunityPost::getId, postId)
                .setSql(sql));
    }

    private Character resolveLinkedCharacter(Long userId, Long characterId) {
        if (characterId == null) {
            return null;
        }
        return assertCharacterOwnedBy(userId, characterId);
    }

    private Character assertCharacterOwnedBy(Long ownerUserId, Long characterId) {
        Character entity = characterMapper.selectById(characterId);
        if (entity == null || !Objects.equals(entity.getOwnerUserId(), ownerUserId)) {
            throw new BusinessException(ErrorCode.CHARACTER_NOT_FOUND);
        }
        return entity;
    }

    private Map<Long, Character> loadCharacters(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Collections.emptyMap();
        }
        List<Long> distinct = ids.stream().filter(Objects::nonNull).distinct().toList();
        if (distinct.isEmpty()) {
            return Collections.emptyMap();
        }
        List<Character> characters = characterMapper.selectBatchIds(distinct);
        Map<Long, Character> map = new HashMap<>();
        for (Character c : characters) {
            map.put(c.getId(), c);
        }
        return map;
    }

    private User loadUser(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }
        return user;
    }

    private Map<Long, User> loadUsers(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Collections.emptyMap();
        }
        List<Long> distinct = ids.stream().filter(Objects::nonNull).distinct().toList();
        if (distinct.isEmpty()) {
            return Collections.emptyMap();
        }
        List<User> users = userMapper.selectBatchIds(distinct);
        Map<Long, User> map = new HashMap<>();
        for (User u : users) {
            map.put(u.getId(), u);
        }
        return map;
    }

    private Set<Long> loadLikedPostIds(Long viewerId, List<Long> postIds) {
        if (viewerId == null || postIds == null || postIds.isEmpty()) {
            return Set.of();
        }
        List<CommunityLike> likes = communityLikeMapper.selectList(new LambdaQueryWrapper<CommunityLike>()
                .eq(CommunityLike::getUserId, viewerId)
                .in(CommunityLike::getPostId, postIds));
        return likes.stream().map(CommunityLike::getPostId).collect(Collectors.toCollection(HashSet::new));
    }

    private static int clampLimit(int limit) {
        if (limit <= 0) {
            return 20;
        }
        return Math.min(limit, 50);
    }

    private static String trimPreview(String content) {
        if (content == null || content.isBlank()) {
            return "分享了一条动态";
        }
        String t = content.trim();
        return t.length() > 80 ? t.substring(0, 80) : t;
    }
}
