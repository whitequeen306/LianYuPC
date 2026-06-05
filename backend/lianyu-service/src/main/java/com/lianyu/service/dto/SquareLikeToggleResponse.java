package com.lianyu.service.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class SquareLikeToggleResponse {
    private boolean liked;
    private long likeCount;
}
