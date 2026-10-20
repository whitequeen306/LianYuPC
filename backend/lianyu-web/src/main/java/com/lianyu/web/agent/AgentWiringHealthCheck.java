package com.lianyu.web.agent;

import com.lianyu.service.agent.AgentImagePort;
import com.lianyu.service.agent.AgentMemoryPort;
import com.lianyu.service.agent.AgentRuntimePort;
import com.lianyu.service.agent.AgentStreamPort;
import com.lianyu.service.agent.AgentTextPort;
import com.lianyu.service.agent.ChatTurnPort;
import com.lianyu.service.agent.TtsPort;
import com.lianyu.service.agent.VoiceEnrollmentPort;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * AGENT 模式接线自检。
 *
 * <p>为什么需要：第 5 轮发现 `ChatTurnFacade` 的文本 agent 分支被误删 ——
 * 字段还在、注入还在，但调用点没了。于是「模式设为 AGENT」时实际仍全走 Java，
 * 而所有单元测试都是绿的（它们各自测端口，不测调用点）。
 *
 * <p>这类故障的特征是**静默**：没报错、没降级日志，只是从没发生。
 * 启动时把「AGENT 模式 + 缺哪个端口」直接打成 ERROR，比事后翻日志便宜得多。
 */
@Slf4j
@Component
public class AgentWiringHealthCheck {

    private final ObjectProvider<AgentRuntimePort> runtimePort;
    private final ObjectProvider<AgentStreamPort> streamPort;
    private final ObjectProvider<AgentTextPort> textPort;
    private final ObjectProvider<AgentMemoryPort> memoryPort;
    private final ObjectProvider<AgentImagePort> imagePort;
    private final ObjectProvider<ChatTurnPort> chatTurnPort;
    private final ObjectProvider<TtsPort> ttsPort;
    private final ObjectProvider<VoiceEnrollmentPort> voiceEnrollmentPort;

    public AgentWiringHealthCheck(ObjectProvider<AgentRuntimePort> runtimePort,
                                  ObjectProvider<AgentStreamPort> streamPort,
                                  ObjectProvider<AgentTextPort> textPort,
                                  ObjectProvider<AgentMemoryPort> memoryPort,
                                  ObjectProvider<AgentImagePort> imagePort,
                                  ObjectProvider<ChatTurnPort> chatTurnPort,
                                  ObjectProvider<TtsPort> ttsPort,
                                  ObjectProvider<VoiceEnrollmentPort> voiceEnrollmentPort) {
        this.runtimePort = runtimePort;
        this.streamPort = streamPort;
        this.textPort = textPort;
        this.memoryPort = memoryPort;
        this.imagePort = imagePort;
        this.chatTurnPort = chatTurnPort;
        this.ttsPort = ttsPort;
        this.voiceEnrollmentPort = voiceEnrollmentPort;
    }

    @Order(200)
    @EventListener(ApplicationReadyEvent.class)
    public void checkOnStartup() {
        Map<String, Boolean> report = inspect();
        List<String> missing = new ArrayList<>();
        report.forEach((name, present) -> {
            if (!present) {
                missing.add(name);
            }
        });
        if (missing.isEmpty()) {
            log.info("Agent runtime — all ports wired: {}", report);
            return;
        }
        log.error("Agent runtime ports missing: {} — those chains will fail instead of falling back",
                missing);
    }

    /** 各端口是否已装配（供测试与运维接口复用）。 */
    public Map<String, Boolean> inspect() {
        Map<String, Boolean> report = new LinkedHashMap<>();
        report.put("proactiveTurn(AgentRuntimePort)", runtimePort.getIfAvailable() != null);
        report.put("stream(AgentStreamPort)", streamPort.getIfAvailable() != null);
        report.put("text(AgentTextPort)", textPort.getIfAvailable() != null);
        report.put("memory(AgentMemoryPort)", memoryPort.getIfAvailable() != null);
        report.put("image(AgentImagePort)", imagePort.getIfAvailable() != null);
        report.put("chatTurn(ChatTurnPort)", chatTurnPort.getIfAvailable() != null);
        report.put("tts(TtsPort)", ttsPort.getIfAvailable() != null);
        report.put("voiceEnrollment(VoiceEnrollmentPort)", voiceEnrollmentPort.getIfAvailable() != null);
        return report;
    }

    public boolean isAgentMode() {
        return true;
    }
}
