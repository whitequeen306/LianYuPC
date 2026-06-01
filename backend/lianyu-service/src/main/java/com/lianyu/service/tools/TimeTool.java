package com.lianyu.service.tools;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

@Component
public class TimeTool {

    @Value("${lianyu.tools.time.zone-id:Asia/Shanghai}")
    private String zoneId;

    public String readCurrentTimeFact() {
        ZoneId zone = ZoneId.of(zoneId);
        ZonedDateTime now = ZonedDateTime.now(zone);
        String formatted = now.format(DateTimeFormatter.ofPattern("yyyy年MM月dd日 HH:mm:ss"));
        return "当前真实时间：" + formatted + "（" + zone + "）。如果用户问今天、现在、几点、星期几等时间相关问题，必须以这个时间为准。";
    }
}
