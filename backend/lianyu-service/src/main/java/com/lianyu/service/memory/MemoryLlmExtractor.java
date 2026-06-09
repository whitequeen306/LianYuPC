package com.lianyu.service.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lianyu.common.constant.AiConstants;
import com.lianyu.dao.entity.Message;
import com.lianyu.dao.enums.MemoryType;
import com.lianyu.service.ai.AiChatService;
import com.lianyu.service.dto.AiChatRequest;
import com.lianyu.service.dto.ChatResult;
import com.lianyu.service.dto.MessageDto;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class MemoryLlmExtractor {

    private final AiChatService aiChatService;
    private final ObjectMapper objectMapper;

    @Value("${lianyu.memory.extraction.model:deepseek-v4-flash}")
    private String extractionModel;

    @Value("${lianyu.memory.extraction.max-context-messages:12}")
    private int maxContextMessages;

    public List<ExtractedMemory> extract(Long userId, List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        try {
            String context = buildContext(messages);
            String systemPrompt = """
                    你是长期记忆提取器。从用户消息中识别值得长期记住的信息。
                    规则：
                    1) 只提取用户明确表达的个人信息、偏好、情绪、事件、习惯、关系；
                    2) 不要记录寒暄、玩笑、提问句、纯表情；
                    3) 结构化事实用 summary 格式：【长期记忆/槽位】内容，槽位可为姓名/爱好/忌口/偏好/禁忌/事件/习惯；
                    4) 关系/情绪/仪式类可用简短摘要句；
                    5) importance 范围 0~1，越重要越高；
                    6) 仅输出 JSON，不要 markdown：
                    {"memories":[{"summary":"...","memoryType":"FACT|EMOTION|RELATION|RITUAL","importance":0.75,"sourceMsgId":123}]}
                    若无值得记住的内容，返回 {"memories":[]}。
                    """;

            AiChatRequest request = new AiChatRequest();
            request.setProvider(AiConstants.PLATFORM_PROVIDER);
            request.setModel(extractionModel);
            request.setTemperature(0.1);
            List<MessageDto> dtos = new ArrayList<>();
            MessageDto system = new MessageDto();
            system.setRole("system");
            system.setContent(systemPrompt);
            dtos.add(system);
            MessageDto user = new MessageDto();
            user.setRole("user");
            user.setContent("最近对话：\n" + context);
            dtos.add(user);
            request.setMessages(dtos);

            ChatResult result = aiChatService.chatBlocking(userId, request);
            if (result == null || result.getContent() == null || result.getContent().isBlank()) {
                return List.of();
            }
            return parseMemories(extractJsonObject(result.getContent().trim()));
        } catch (Exception e) {
            log.debug("Memory LLM extraction skipped: {}", e.getMessage());
            return List.of();
        }
    }

    private List<ExtractedMemory> parseMemories(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode memories = root.path("memories");
            if (!memories.isArray()) {
                return List.of();
            }
            List<ExtractedMemory> result = new ArrayList<>();
            for (JsonNode node : memories) {
                String summary = node.path("summary").asText("").trim();
                if (summary.isBlank()) {
                    continue;
                }
                MemoryType type = parseMemoryType(node.path("memoryType").asText("FACT"));
                double importance = node.path("importance").asDouble(0.6);
                long sourceMsgId = node.path("sourceMsgId").asLong(0L);
                result.add(new ExtractedMemory(
                        summary,
                        type,
                        sourceMsgId > 0 ? sourceMsgId : null,
                        importance));
            }
            return result;
        } catch (Exception e) {
            log.debug("Memory LLM JSON parse failed: {}", e.getMessage());
            return List.of();
        }
    }

    private MemoryType parseMemoryType(String raw) {
        try {
            return MemoryType.valueOf(raw.trim().toUpperCase());
        } catch (Exception e) {
            return MemoryType.FACT;
        }
    }

    private String buildContext(List<Message> messages) {
        int max = Math.max(1, maxContextMessages);
        int start = Math.max(0, messages.size() - max);
        List<String> lines = new ArrayList<>();
        for (int i = start; i < messages.size(); i++) {
            Message msg = messages.get(i);
            if (!"USER".equalsIgnoreCase(msg.getRole()) || msg.getContent() == null) {
                continue;
            }
            String content = msg.getContent().trim();
            if (content.isEmpty()) {
                continue;
            }
            if (content.length() > 160) {
                content = content.substring(0, 160) + "...";
            }
            lines.add("msgId=" + msg.getId() + " 用户: " + content);
        }
        return lines.isEmpty() ? "(空)" : String.join("\n", lines);
    }

    private String extractJsonObject(String text) {
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return text;
    }
}
