package com.lianyu.service.dto;

import lombok.Data;

@Data
public class UpdateUserSettingsRequest {
    private Boolean showCharactersOnProfile;
    private Boolean communityPushEnabled;
}
