package com.lianyu.service.dto;

import lombok.Data;

import java.util.List;

@Data
public class MarkNotificationReadRequest {
    private Boolean all;
    private Long conversationId;
    private List<Long> ids;
}
