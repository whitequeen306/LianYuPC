package com.lianyu.web.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lianyu.service.agent.SceneInstructionPort;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * {@link SceneInstructionPort} 的 agent 实现：向 Python 取场景 instruction。
 *
 * <p>失败返回 null（不抛）：日记生成是夜间后台任务，取不到文案就跳过这一篇，
 * 明天还会再跑；绝不能因为一次网络抖动把整批日记打红。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentSceneInstructionAdapter implements SceneInstructionPort {

    private final AgentRuntimeClient client;
    private final ObjectMapper objectMapper;

    @Override
    public String instruction(String scene, String outputLanguage) {
        if (scene == null || scene.isBlank()) {
            return null;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("scene", scene);
        body.put("language", outputLanguage == null ? "zh" : outputLanguage);
        try {
            String raw = client.postJson("/agent/prompt/scene-instruction", 0L, body);
            if (raw == null || raw.isBlank()) {
                return null;
            }
            JsonNode root = objectMapper.readTree(raw);
            String text = root.path("instruction").asText("");
            return text.isBlank() ? null : text;
        } catch (Exception e) {
            log.warn("scene instruction fetch failed scene={} lang={}: {}", scene, outputLanguage, e.toString());
            return null;
        }
    }
}
