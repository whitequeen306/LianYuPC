package com.lianyu.service.dto;

import lombok.Data;

@Data
public class AddCharacterFromSquareRequest {

    /** 已废弃：始终按现实城市处理，忽略 fictional */
    private String cityMode = "real";

    /** 用户所在城市（必填，写入 settings.city） */
    private String city;
}
