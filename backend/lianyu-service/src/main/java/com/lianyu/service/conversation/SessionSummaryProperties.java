package com.lianyu.service.conversation;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "lianyu.session.summary")
public class SessionSummaryProperties {

    private boolean enabled = true;
    private int rawWindow = 32;
    private int slideBatchMin = 6;
    private String model = "deepseek-v4-flash";
    private long redisTtlHours = 72;
    private int targetChars = 600;
    private int softMaxChars = 900;
    private int hardMaxChars = 1200;
    private boolean structured = true;
    private int staleMinutes = 30;
}
