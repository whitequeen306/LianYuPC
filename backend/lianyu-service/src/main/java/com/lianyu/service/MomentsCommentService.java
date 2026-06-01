package com.lianyu.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lianyu.common.base.ErrorCode;
import com.lianyu.common.exception.BusinessException;
import com.lianyu.dao.entity.Character;
import com.lianyu.dao.entity.MomentsComment;
import com.lianyu.dao.entity.MomentsPost;
import com.lianyu.dao.mapper.CharacterMapper;
import com.lianyu.dao.mapper.MomentsCommentMapper;
import com.lianyu.dao.mapper.MomentsPostMapper;
import com.lianyu.service.dto.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class MomentsCommentService {

    public static final String AUTHOR_USER = "USER";
    public static final String AUTHOR_CHARACTER = "CHARACTER";
    public static final String SOURCE_MANUAL = "MANUAL";
    public static final String SOURCE_AUTO_AUTHOR_REPLY = "AUTO_AUTHOR_REPLY";
    public static final String SOURCE_AUTO_PEER_COMMENT = "AUTO_PEER_COMMENT";

    private final MomentsPostMapper momentsPostMapper;
    private final MomentsCommentMapper momentsCommentMapper;
    private final CharacterMapper characterMapper;
    private final NotificationService notificationService;

    @Lazy
    private final MomentsCommentOrchestrator momentsCommentOrchestrator;

    public MomentCommentListResponse listComments(Long userId, Long postId, Long cursor, int limit) {
        MomentsPost post = findOwnedPost(userId, postId);
        int realLimit = Math.min(Math.max(1, limit), 100);

        LambdaQueryWrapper<MomentsComment> qw = new LambdaQueryWrapper<MomentsComment>()
                .eq(MomentsComment::getPostId, post.getId())
                .orderByAsc(MomentsComment::getId);
        if (cursor != null && cursor > 0) {
            qw.gt(MomentsComment::getId, cursor);
        }
        qw.last("LIMIT " + (realLimit + 1));

        List<MomentsComment> rows = momentsCommentMapper.selectList(qw);
        boolean hasMore = rows.size() > realLimit;
        if (hasMore) {
            rows = new ArrayList<>(rows.subList(0, realLimit));
        }

        Long total = momentsCommentMapper.selectCount(new LambdaQueryWrapper<MomentsComment>()
                .eq(MomentsComment::getPostId, postId));

        Map<Long, Character> characterMap = loadCharacterMap(rows);
        List<MomentCommentResponse> items = rows.stream()
                .map(r -> toResponse(r, characterMap))
                .toList();

        Long nextCursor = hasMore && !rows.isEmpty() ? rows.get(rows.size() - 1).getId() : null;
        return MomentCommentListResponse.builder()
                .items(items)
                .nextCursor(nextCursor)
                .hasMore(hasMore)
                .totalCount(total == null ? 0 : total.intValue())
                .build();
    }

    @Transactional
    public MomentCommentResponse addUserComment(Long userId, Long postId, CreateMomentCommentRequest request) {
        MomentsPost post = findOwnedPost(userId, postId);
        String content = sanitizeComment(request.getContent());
        if (content.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "评论内容不能为空");
        }

        Long parentId = request.getParentId();
        Long rootId = null;
        if (parentId != null) {
            MomentsComment parent = momentsCommentMapper.selectById(parentId);
            if (parent == null || !parent.getPostId().equals(postId)) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "回复目标不存在");
            }
            rootId = parent.getRootId() != null ? parent.getRootId() : parent.getId();
        }

        MomentsComment row = new MomentsComment();
        row.setPostId(postId);
        row.setUserId(userId);
        row.setAuthorType(AUTHOR_USER);
        row.setCharacterId(null);
        row.setParentId(parentId);
        row.setRootId(rootId);
        row.setContent(content);
        row.setSourceType(SOURCE_MANUAL);
        momentsCommentMapper.insert(row);
        if (row.getRootId() == null) {
            row.setRootId(row.getId());
            momentsCommentMapper.updateById(row);
        }

        notificationService.notifyMomentComment(
                userId,
                post.getConversationId(),
                post.getCharacterId(),
                "你",
                content,
                postId
        );

        momentsCommentOrchestrator.afterCommentAdded(postId, row.getId());
        return toResponse(row, Map.of());
    }

    @Transactional
    public MomentsComment insertCharacterComment(MomentsPost post,
                                                Character character,
                                                String content,
                                                String sourceType,
                                                Long parentId,
                                                Long rootId,
                                                String idempotencyKey) {
        if (content == null || content.isBlank()) {
            return null;
        }
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            Long exists = momentsCommentMapper.selectCount(new LambdaQueryWrapper<MomentsComment>()
                    .eq(MomentsComment::getIdempotencyKey, idempotencyKey));
            if (exists != null && exists > 0) {
                return null;
            }
        }

        MomentsComment row = new MomentsComment();
        row.setPostId(post.getId());
        row.setUserId(post.getUserId());
        row.setAuthorType(AUTHOR_CHARACTER);
        row.setCharacterId(character.getId());
        row.setParentId(parentId);
        row.setRootId(rootId);
        row.setContent(sanitizeComment(content));
        row.setSourceType(sourceType);
        row.setIdempotencyKey(idempotencyKey);
        try {
            momentsCommentMapper.insert(row);
        } catch (Exception e) {
            log.debug("Character comment skipped: postId={}, key={}, reason={}",
                    post.getId(), idempotencyKey, e.getMessage());
            return null;
        }
        if (row.getRootId() == null && parentId == null) {
            row.setRootId(row.getId());
            momentsCommentMapper.updateById(row);
        }

        notificationService.notifyMomentComment(
                post.getUserId(),
                post.getConversationId(),
                character.getId(),
                character.getName(),
                row.getContent(),
                post.getId()
        );
        return row;
    }

    public int countByPostId(Long postId) {
        Long c = momentsCommentMapper.selectCount(new LambdaQueryWrapper<MomentsComment>()
                .eq(MomentsComment::getPostId, postId));
        return c == null ? 0 : c.intValue();
    }

    public Map<Long, Integer> countByPostIds(Collection<Long> postIds) {
        if (postIds == null || postIds.isEmpty()) {
            return Map.of();
        }
        List<MomentsComment> all = momentsCommentMapper.selectList(new LambdaQueryWrapper<MomentsComment>()
                .in(MomentsComment::getPostId, postIds));
        Map<Long, Integer> map = new HashMap<>();
        for (MomentsComment c : all) {
            map.merge(c.getPostId(), 1, Integer::sum);
        }
        return map;
    }

    private MomentsPost findOwnedPost(Long userId, Long postId) {
        MomentsPost post = momentsPostMapper.selectById(postId);
        if (post == null || !post.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "动态不存在");
        }
        return post;
    }

    private Map<Long, Character> loadCharacterMap(List<MomentsComment> rows) {
        Set<Long> ids = rows.stream()
                .map(MomentsComment::getCharacterId)
                .filter(id -> id != null && id > 0)
                .collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, Character> map = new HashMap<>();
        characterMapper.selectBatchIds(ids).forEach(c -> map.put(c.getId(), c));
        return map;
    }

    private MomentCommentResponse toResponse(MomentsComment row, Map<Long, Character> characterMap) {
        Character character = row.getCharacterId() == null ? null : characterMap.get(row.getCharacterId());
        String name = AUTHOR_USER.equals(row.getAuthorType()) ? "你" : (character != null ? character.getName() : "角色");
        return MomentCommentResponse.builder()
                .id(row.getId())
                .postId(row.getPostId())
                .authorType(row.getAuthorType())
                .characterId(row.getCharacterId())
                .characterName(name)
                .characterAvatarUrl(character != null ? character.getAvatarUrl() : null)
                .userDisplayName(AUTHOR_USER.equals(row.getAuthorType()) ? "你" : null)
                .parentId(row.getParentId())
                .rootId(row.getRootId())
                .content(row.getContent())
                .sourceType(row.getSourceType())
                .createdAt(row.getCreatedAt())
                .build();
    }

    private String sanitizeComment(String raw) {
        if (raw == null) {
            return "";
        }
        String text = raw.trim().replaceAll("[\\r\\n]+", " ").replaceAll("\\s{2,}", " ");
        if (text.length() > 500) {
            text = text.substring(0, 500);
        }
        return text;
    }
}
