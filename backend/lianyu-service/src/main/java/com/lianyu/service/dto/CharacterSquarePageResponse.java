package com.lianyu.service.dto;

import java.util.List;
import java.util.Set;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class CharacterSquarePageResponse {
    private List<CharacterSquareTemplateResponse> records;
    private long total;
    private int page;
    private int size;
    /** 当前 UI 语言下全部可选标签（用于筛选栏） */
    private List<String> tags;
    /** 当前用户已点赞的模板 ID */
    private Set<Long> userLikes;
}
