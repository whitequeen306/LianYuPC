package com.lianyu.service.dto;

import java.time.LocalDateTime;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class SquareCommentResponse {
    private Long id;
    private Long templateId;
    private Long userId;
    private String content;
    private LocalDateTime createdAt;
    private boolean isMine;
}
