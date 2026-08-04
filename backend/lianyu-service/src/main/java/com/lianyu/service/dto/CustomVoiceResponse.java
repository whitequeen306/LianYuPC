package com.lianyu.service.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class CustomVoiceResponse {
    private Long characterId;
    private String provider;
    private String status;
    private String errorMessage;
    /** Present for local providers; public URL for client playback/cache. */
    private String refAudioUrl;
    private String refText;
    /** Local SoVITS base, or DashScope API origin. */
    private String endpoint;
    private String httpModel;
    private String realtimeModel;
    private boolean hasApiKey;
    private boolean voiceCallReady;
    /** UI hints — recommended defaults (not necessarily what user saved). */
    private String recommendedApiBase;
    private String recommendedHttpModel;
    private String recommendedRealtimeModel;
}
