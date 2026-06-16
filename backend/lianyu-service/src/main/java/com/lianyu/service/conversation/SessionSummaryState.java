package com.lianyu.service.conversation;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SessionSummaryState {

    private String text = "";
    private long lastSummarizedSeq;
    private LocalDateTime updatedAt;
}
