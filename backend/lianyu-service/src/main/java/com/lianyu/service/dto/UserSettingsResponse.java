package com.lianyu.service.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class UserSettingsResponse {
    private boolean showCharactersOnProfile;
    /** 识图来源：platform | followText | provider */
    private String visionSourceMode;
    /** mode=provider 时的识图 vault 别名 */
    private String visionSourceProvider;
}
