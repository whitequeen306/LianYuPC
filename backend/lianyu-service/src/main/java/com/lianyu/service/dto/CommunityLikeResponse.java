package com.lianyu.service.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class CommunityLikeResponse {
    private boolean liked;
    private int likeCount;
}
