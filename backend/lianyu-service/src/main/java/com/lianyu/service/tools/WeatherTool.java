package com.lianyu.service.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

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

    public String readCurrentWeatherFact(String city) {
        if (!enabled || city == null || city.isBlank()) {
            return "";
        }
        String trimmedCity = city.trim();
        String cacheKey = "tools:weather:" + trimmedCity;
        try {
            String cached = redisTemplate.opsForValue().get(cacheKey);
            if (cached != null && !cached.isBlank()) {
                return cached;
            }
        } catch (Exception e) {
            log.debug("weather cache read failed: {}", e.getMessage());
        }

        String weatherFact = fetchWeatherFact(trimmedCity);
        if (weatherFact.isBlank()) {
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
            String body = RestClient.create()
                    .get()
                    .uri(url)
                    .retrieve()
                    .body(String.class);
            if (body == null || body.isBlank()) {
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
            if (temp.isBlank() && desc.isBlank()) {
                return "";
            }
            return String.format(
                    "当前城市天气（%s）：%s，气温%s°C，体感%s°C，湿度%s%%，风速%skm/h。若用户问天气、穿衣、是否带伞等，优先据此回答。",
                    city,
                    desc.isBlank() ? "未知" : desc,
                    temp.isBlank() ? "?" : temp,
                    feelsLike.isBlank() ? "?" : feelsLike,
                    humidity.isBlank() ? "?" : humidity,
                    wind.isBlank() ? "?" : wind
            );
        } catch (Exception e) {
            log.debug("weather fetch skipped for city={}, reason={}", city, e.getMessage());
            return "";
        }
    }
}
