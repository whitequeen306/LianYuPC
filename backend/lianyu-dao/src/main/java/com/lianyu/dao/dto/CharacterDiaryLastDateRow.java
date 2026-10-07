package com.lianyu.dao.dto;

import lombok.Data;

import java.time.LocalDateTime;

/** 角色最后一篇日记时间（任何来源），日记两天保底等节奏控制使用。 */
@Data
public class CharacterDiaryLastDateRow {
    private Long characterId;
    private LocalDateTime lastDiaryAt;
}
