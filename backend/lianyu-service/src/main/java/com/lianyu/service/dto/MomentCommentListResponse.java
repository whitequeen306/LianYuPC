package com.lianyu.service.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class MomentCommentListResponse {
    private List<MomentCommentResponse> items;
    private Long nextCursor;
    private boolean hasMore;
    private int totalCount;
}
