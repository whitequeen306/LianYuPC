package com.lianyu.service.tools;

import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class TimeTool {

    @Value("${lianyu.tools.time.zone-id:Asia/Shanghai}")
    private String defaultZoneId;

    @Tool(name = "get_current_time", description = """
            获取当前真实日期与时间（应用配置时区）。
            当用户询问现在几点、今天几号、星期几、日期等与「当前时间」相关的问题时调用。""")
    public String getCurrentTime() {
        ZoneId zone = resolveZone();
        return readCurrentTimeFact(zone);
    }

    public String readCurrentTimeFact(ZoneId zone) {
        ZonedDateTime now = ZonedDateTime.now(zone);
        String formatted = now.format(DateTimeFormatter.ofPattern(
                "yyyy年MM月dd日 EEEE HH:mm:ss", Locale.CHINESE));
        DayPart part = resolveDayPart(now.toLocalTime());
        return "当前真实时间：" + formatted + "（" + zone + "）。"
                + "当前时段：" + part.label() + "。"
                + "时段问候只能使用：" + part.allowedGreetings() + "。"
                + "禁止使用：" + part.forbiddenGreetings() + "。"
                + "涉及今天、现在、几点、星期几、早晚安等，必须以本条时间为准；"
                + "历史消息里的「早上好/晚安」等只代表当时，不代表现在。";
    }

    /**
     * Explicit day-part for prompt grounding. Package-visible for tests.
     */
    static DayPart resolveDayPart(LocalTime time) {
        int minuteOfDay = time.getHour() * 60 + time.getMinute();
        // 00:00–04:59 深夜 / 05:00–08:59 清晨 / 09:00–11:29 上午
        // 11:30–13:29 中午 / 13:30–17:29 下午 / 17:30–19:29 傍晚
        // 19:30–22:59 晚上 / 23:00–23:59 深夜
        if (minuteOfDay < 5 * 60) {
            return DayPart.LATE_NIGHT;
        }
        if (minuteOfDay < 9 * 60) {
            return DayPart.EARLY_MORNING;
        }
        if (minuteOfDay < 11 * 60 + 30) {
            return DayPart.MORNING;
        }
        if (minuteOfDay < 13 * 60 + 30) {
            return DayPart.NOON;
        }
        if (minuteOfDay < 17 * 60 + 30) {
            return DayPart.AFTERNOON;
        }
        if (minuteOfDay < 19 * 60 + 30) {
            return DayPart.DUSK;
        }
        if (minuteOfDay < 23 * 60) {
            return DayPart.EVENING;
        }
        return DayPart.LATE_NIGHT;
    }

    enum DayPart {
        EARLY_MORNING("清晨", "早安、早上好、早", "晚安、晚上好、午安"),
        MORNING("上午", "早上好、上午好、早", "晚安、晚上好"),
        NOON("中午", "中午好、午安", "早上好、晚安、晚上好"),
        AFTERNOON("下午", "下午好", "早上好、晚安"),
        DUSK("傍晚", "傍晚好、晚上好", "早上好、午安"),
        EVENING("晚上", "晚上好、晚安（若用户要睡）", "早上好、早安、上午好、午安"),
        LATE_NIGHT("深夜", "还没睡吗、夜深了、晚安", "早上好、早安、上午好、午安、下午好");

        private final String label;
        private final String allowed;
        private final String forbidden;

        DayPart(String label, String allowed, String forbidden) {
            this.label = label;
            this.allowed = allowed;
            this.forbidden = forbidden;
        }

        String label() {
            return label;
        }

        String allowedGreetings() {
            return allowed;
        }

        String forbiddenGreetings() {
            return forbidden;
        }
    }

    private ZoneId resolveZone() {
        ChatToolContext.Scope scope = ChatToolContext.current();
        if (scope != null) {
            String city = scope.effectiveCity();
            if (city != null && !city.isBlank()) {
                return CityTimeZone.fromCity(city);
            }
        }
        return ZoneId.of(defaultZoneId);
    }

    /** Convenience: read time fact with the configured default zone (used by proactive context where Tool context may not be set). */
    public String readCurrentTimeFact() {
        return readCurrentTimeFact(ZoneId.of(defaultZoneId));
    }
}
