package com.lianyu.service.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class UserSettingsResponse {
    private boolean showCharactersOnProfile;
    /** Whether to receive community post toasts; default true when unset. */
    private boolean communityPushEnabled;
}
