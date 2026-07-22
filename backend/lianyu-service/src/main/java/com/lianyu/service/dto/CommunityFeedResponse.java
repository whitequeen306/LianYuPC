package com.lianyu.service.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class CommunityFeedResponse {
    private List<CommunityPostResponse> items;
    private Long nextCursor;
    private boolean hasMore;
}
