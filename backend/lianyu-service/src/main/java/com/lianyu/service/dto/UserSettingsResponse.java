package com.lianyu.service.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class UserSettingsResponse {
    private boolean showCharactersOnProfile;
}
