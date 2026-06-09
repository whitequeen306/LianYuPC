package com.lianyu.service.square;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lianyu.common.base.ErrorCode;
import com.lianyu.common.exception.BusinessException;
import com.lianyu.common.util.UserInputSanitizer;
import com.lianyu.dao.entity.CharacterSquareTemplate;
import com.lianyu.dao.entity.SquareComment;
import com.lianyu.dao.mapper.CharacterSquareTemplateMapper;
import com.lianyu.dao.mapper.SquareCommentMapper;
import com.lianyu.service.dto.SquareCommentResponse;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SquareCommentService {

    private static final int MAX_LIST = 30;
    private static final int MAX_CONTENT_CHARS = 60;

    private final SquareCommentMapper squareCommentMapper;
    private final CharacterSquareTemplateMapper templateMapper;

    public List<SquareCommentResponse> listByTemplate(Long templateId, Long viewerUserId) {
        requireEnabledTemplate(templateId);
        List<SquareComment> rows = squareCommentMapper.selectList(
                new LambdaQueryWrapper<SquareComment>()
                        .eq(SquareComment::getTemplateId, templateId)
                        .orderByDesc(SquareComment::getCreatedAt)
                        .last("LIMIT " + MAX_LIST));
        return rows.stream()
                .map(row -> toResponse(row, viewerUserId))
                .toList();
    }

    @Transactional
    public SquareCommentResponse upsert(Long templateId, Long userId, String rawContent) {
        requireEnabledTemplate(templateId);
        String content = sanitizeContent(rawContent);

        SquareComment existing = squareCommentMapper.selectOne(
                new LambdaQueryWrapper<SquareComment>()
                        .eq(SquareComment::getTemplateId, templateId)
                        .eq(SquareComment::getUserId, userId)
                        .last("LIMIT 1"));

        if (existing != null) {
            existing.setContent(content);
            squareCommentMapper.updateById(existing);
            return toResponse(existing, userId);
        }

        SquareComment row = new SquareComment();
        row.setTemplateId(templateId);
        row.setUserId(userId);
        row.setContent(content);
        squareCommentMapper.insert(row);
        return toResponse(row, userId);
    }

    @Transactional
    public void deleteOwn(Long templateId, Long userId) {
        requireEnabledTemplate(templateId);
        int removed = squareCommentMapper.delete(
                new LambdaQueryWrapper<SquareComment>()
                        .eq(SquareComment::getTemplateId, templateId)
                        .eq(SquareComment::getUserId, userId));
        if (removed == 0) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "评语不存在");
        }
    }

    private void requireEnabledTemplate(Long templateId) {
        CharacterSquareTemplate template = templateMapper.selectById(templateId);
        if (template == null || template.getIsEnabled() == null || template.getIsEnabled() != 1) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "角色模板不存在");
        }
    }

    private String sanitizeContent(String raw) {
        String cleaned = UserInputSanitizer.sanitizeGenerationDescription(raw);
        if (cleaned.length() > MAX_CONTENT_CHARS) {
            cleaned = cleaned.substring(0, MAX_CONTENT_CHARS);
        }
        cleaned = cleaned.trim();
        if (cleaned.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "评语不能为空");
        }
        return cleaned;
    }

    private SquareCommentResponse toResponse(SquareComment row, Long viewerUserId) {
        return SquareCommentResponse.builder()
                .id(row.getId())
                .templateId(row.getTemplateId())
                .userId(row.getUserId())
                .content(row.getContent())
                .createdAt(row.getCreatedAt())
                .isMine(viewerUserId != null && viewerUserId.equals(row.getUserId()))
                .build();
    }
}
