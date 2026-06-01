package com.lianyu.service.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class CharacterSquarePageResponse {
    private List<CharacterSquareTemplateResponse> records;
    private long total;
    private int page;
    private int size;
    /** 当前 UI 语言下全部可选标签（用于筛选栏） */
    private List<String> tags;
}
