package com.lianyu.service.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalTime;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class TimeToolTest {

    private final TimeTool timeTool = new TimeTool();

    @Test
    void resolvesEveningAndForbidsMorningGreeting() {
        assertThat(TimeTool.resolveDayPart(LocalTime.of(22, 0)))
                .isEqualTo(TimeTool.DayPart.EVENING);
        assertThat(TimeTool.resolveDayPart(LocalTime.of(22, 0)).forbiddenGreetings())
                .contains("早上好");
        assertThat(TimeTool.resolveDayPart(LocalTime.of(22, 0)).allowedGreetings())
                .contains("晚上好");
    }

    @Test
    void resolvesMorningAndForbidsGoodnight() {
        assertThat(TimeTool.resolveDayPart(LocalTime.of(8, 0)))
                .isEqualTo(TimeTool.DayPart.EARLY_MORNING);
        assertThat(TimeTool.resolveDayPart(LocalTime.of(8, 0)).forbiddenGreetings())
                .contains("晚安");
    }

    @Test
    void factIncludesDayPartAndHistoryWarning() {
        String fact = timeTool.readCurrentTimeFact(ZoneId.of("Asia/Shanghai"));
        assertThat(fact).contains("当前真实时间：");
        assertThat(fact).contains("当前时段：");
        assertThat(fact).contains("时段问候只能使用：");
        assertThat(fact).contains("历史消息里的");
    }
}
