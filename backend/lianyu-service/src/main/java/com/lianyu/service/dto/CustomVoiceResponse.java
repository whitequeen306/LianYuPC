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
    private String endpoint;
    private boolean hasApiKey;
    private boolean voiceCallReady;
}
