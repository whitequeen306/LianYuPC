package com.lianyu.service.tools;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@RequiredArgsConstructor
public class ToolManager {

    private final TimeTool timeTool;
    private final WeatherTool weatherTool;

    @Value("${lianyu.tools.default-city:上海}")
    private String defaultCity;

    @Value("${lianyu.tools.weather.enabled:true}")
    private boolean weatherEnabled;

    public ToolFactBundle collectFacts(Map<String, Object> characterSettings) {
        String timeFact = timeTool.readCurrentTimeFact();
        String city = resolveCity(characterSettings);
        String weatherFact = weatherEnabled ? weatherTool.readCurrentWeatherFact(city) : "";
        return new ToolFactBundle(timeFact, weatherFact, city);
    }

    private String resolveCity(Map<String, Object> settings) {
        if (settings == null || settings.isEmpty()) {
            return defaultCity;
        }
        Object city = settings.get("city");
        if (city == null) {
            city = settings.get("location");
        }
        if (city == null) {
            return defaultCity;
        }
        String value = String.valueOf(city).trim();
        return value.isBlank() ? defaultCity : value;
    }
}
