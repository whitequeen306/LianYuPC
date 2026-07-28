package com.lianyu.service.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;

@Data
public class VoiceCallEndRequest {

    /** Wall-clock call duration in seconds (from start to hangup). */
    @Min(0)
    @Max(6 * 60 * 60)
    private int durationSeconds;

    @Valid
    @Size(max = 40)
    private List<VoiceCallTurnSnippet> turns = new ArrayList<>();

    @Data
    public static class VoiceCallTurnSnippet {
        @Size(max = 500)
        private String userText;
        @Size(max = 500)
        private String replyText;
    }
}
