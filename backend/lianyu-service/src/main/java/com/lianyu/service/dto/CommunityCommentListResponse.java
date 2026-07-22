package com.lianyu.service.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class CommunityCommentListResponse {
    private List<CommunityCommentResponse> items;
    private Long nextCursor;
    private boolean hasMore;
}
