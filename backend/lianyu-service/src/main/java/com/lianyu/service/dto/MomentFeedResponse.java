package com.lianyu.service.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class MomentFeedResponse {
    private List<MomentPostResponse> items;
    private Long nextCursor;
    private boolean hasMore;
}
