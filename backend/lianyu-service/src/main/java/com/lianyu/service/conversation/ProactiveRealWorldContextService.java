package com.lianyu.service.conversation;

import cn.hutool.core.util.StrUtil;
import com.lianyu.dao.entity.Character;
import com.lianyu.service.tools.WeatherTool;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 角色主动开口前，同步拉取真实时间与天气，写入 Prompt 供模型生成更贴合现实的问候。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProactiveRealWorldContextService {

    private final WeatherTool weatherTool;

    @Value("${lianyu.tools.default-city:}")
    private String defaultCity;

    @Value("${lianyu.chat.proactive.prefetch-realworld-context:true}")
    private boolean prefetchEnabled;

    /**
     * @return 可追加在 system prompt 末尾的环境块；未启用或查询失败时仍保证有时间信息。
     */
    public String buildBlock(Character character) {
        if (!prefetchEnabled) {
            return "";
        }
        Map<String, Object> settings = character != null ? character.getSettings() : null;
        String city = resolveProactiveCity(settings);
        String weatherFact = StrUtil.isNotBlank(city) ? weatherTool.readCurrentWeatherFact(city) : "";

        StringBuilder sb = new StringBuilder();
        sb.append("\n\n=== 主动开口参考环境（时间以 system 中已注入的当前真实时间为准，请自然融入问候，勿逐条朗读） ===\n");
        if (StrUtil.isNotBlank(weatherFact)) {
            sb.append(weatherFact).append('\n');
        } else {
            sb.append("（未能获取「").append(city).append("」当前天气，可仅结合时段与季节问候。）\n");
            log.debug("Proactive prefetch: weather empty for city={}", city);
        }
        sb.append("""
                写主动消息时可自然参考以上真实时间与天气（如早晚安、冷暖、是否适合出门等），\
                语气仍须完全符合角色设定；早晚安必须与 system 中「当前时段」一致，\
                绝不要因为历史里出现过「早上好」就在晚上再说早上好；\
                不要像播报员念数据，也不要编造与上述不符的时间或天气。\
                主动开口也要像日常私聊：可关心用户今天过得怎样、累不累、吃了没，不要自顾自讲设定剧情或推进世界观故事。""");
        return sb.toString();
    }

    /**
     * 优先角色虚构城市 > 角色设定 city > 用户城市 > 空。
     */
    private String resolveProactiveCity(Map<String, Object> settings) {
        if (settings != null) {
            Object fc = settings.get("fictional_city");
            if (fc instanceof String s && !s.isBlank()) return s.trim();
            Object city = settings.get("city");
            if (city instanceof String s && !s.isBlank()) return s.trim();
        }
        return defaultCity;
    }
}
