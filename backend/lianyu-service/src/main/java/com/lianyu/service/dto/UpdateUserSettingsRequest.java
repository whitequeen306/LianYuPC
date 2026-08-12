package com.lianyu.service.dto;

import lombok.Data;

@Data
public class UpdateUserSettingsRequest {
    private Boolean showCharactersOnProfile;
    private Boolean communityPushEnabled;
    /** 识图来源：platform | followText | provider；null 表示不修改 */
    private String visionSourceMode;
    /** mode=provider 时必填：purpose=vision 的 vault 别名 */
    private String visionSourceProvider;
}
