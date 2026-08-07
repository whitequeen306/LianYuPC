package com.lianyu.service.tools;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Slf4j
@Component
@RequiredArgsConstructor
public class WeatherTool {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @Value("${lianyu.tools.weather.enabled:true}")
    private boolean enabled;

    @Value("${lianyu.tools.weather.base-url:https://wttr.in}")
    private String weatherBaseUrl;

    @Value("${lianyu.tools.weather.cache-minutes:15}")
    private int cacheMinutes;

    @Value("${lianyu.tools.default-city:}")
    private String defaultCity;

    @Tool(name = "get_weather", description = """
            查询指定城市当前天气（气温、体感、湿度、风速、天气描述）。
            当用户问天气、冷不冷、要不要带伞、穿衣建议时调用。city 可省略，将使用角色设定中的城市。""")
    public String getWeather(
            @ToolParam(description = "城市名，如上海、北京；不确定可留空") String city) {
        String resolved = resolveCityForRequest(city);
        String fact = readCurrentWeatherFact(resolved);
        return StrUtil.isBlank(fact) ? "（暂时无法获取该城市天气）" : fact;
    }

    public String readCurrentWeatherFact(String city) {
        if (!enabled || StrUtil.isBlank(city)) {
            return "";
        }
        String trimmedCity = city.trim();
        String cacheKey = "tools:weather:" + trimmedCity;
        try {
            String cached = redisTemplate.opsForValue().get(cacheKey);
            if (StrUtil.isNotBlank(cached)) {
                return cached;
            }
        } catch (Exception e) {
            log.debug("weather cache read failed: {}", e.getMessage());
        }

        String weatherFact = fetchWeatherFact(trimmedCity);
        if (StrUtil.isBlank(weatherFact)) {
            return "";
        }
        try {
            redisTemplate.opsForValue().set(
                    cacheKey, weatherFact, Duration.ofMinutes(Math.max(1, cacheMinutes)));
        } catch (Exception e) {
            log.debug("weather cache write failed: {}", e.getMessage());
        }
        return weatherFact;
    }

    private String fetchWeatherFact(String city) {
        try {
            String encodedCity = URLEncoder.encode(city, StandardCharsets.UTF_8);
            String url = weatherBaseUrl.replaceAll("/$", "") + "/" + encodedCity + "?format=j1";
            String body = com.lianyu.ai.SsrfPinningClientFactory.defaultRestClientBuilder().build()
                    .get()
                    .uri(url)
                    .retrieve()
                    .body(String.class);
            if (StrUtil.isBlank(body)) {
                return "";
            }
            JsonNode root = objectMapper.readTree(body);
            JsonNode current = root.path("current_condition");
            if (!current.isArray() || current.isEmpty()) {
                return "";
            }
            JsonNode item = current.get(0);
            String temp = item.path("temp_C").asText("");
            String feelsLike = item.path("FeelsLikeC").asText("");
            String humidity = item.path("humidity").asText("");
            String wind = item.path("windspeedKmph").asText("");
            String desc = "";
            JsonNode weatherDesc = item.path("weatherDesc");
            if (weatherDesc.isArray() && !weatherDesc.isEmpty()) {
                desc = weatherDesc.get(0).path("value").asText("");
            }
            if (StrUtil.isAllBlank(temp, desc)) {
                return "";
            }
            return String.format(
                    "当前城市天气（%s）：%s，气温%s°C，体感%s°C，湿度%s%%，风速%skm/h。若用户问天气、穿衣、是否带伞等，优先据此回答。",
                    city,
                    StrUtil.blankToDefault(desc, "未知"),
                    StrUtil.blankToDefault(temp, "?"),
                    StrUtil.blankToDefault(feelsLike, "?"),
                    StrUtil.blankToDefault(humidity, "?"),
                    StrUtil.blankToDefault(wind, "?")
            );
        } catch (Exception e) {
            log.debug("weather fetch skipped for city={}, reason={}", city, e.getMessage());
            return "";
        }
    }

    private String resolveCityForRequest(String requestedCity) {
        if (requestedCity != null && !requestedCity.isBlank()) {
            return requestedCity.trim();
        }
        ChatToolContext.Scope scope = ChatToolContext.current();
        if (scope != null) {
            String effective = scope.effectiveCity();
            if (effective != null && !effective.isBlank()) return effective;
        }
        return defaultCity;
    }
}
