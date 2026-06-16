package com.lianyu.service.conversation;

import com.lianyu.common.constant.AiConstants;
import com.lianyu.dao.entity.Message;
import com.lianyu.service.ai.AiChatService;
import com.lianyu.service.dto.AiChatRequest;
import com.lianyu.service.dto.ChatResult;
import com.lianyu.service.dto.MessageDto;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class SessionSummaryMerger {

    private final AiChatService aiChatService;
    private final SessionSummaryProperties properties;

    public String merge(Long userId, String existingSummary, List<Message> pendingMessages) {
        if (pendingMessages == null || pendingMessages.isEmpty()) {
            return normalize(existingSummary);
        }
        try {
            String systemPrompt = buildMergeSystemPrompt();
            String userContent = buildMergeUserContent(existingSummary, pendingMessages);
            String merged = callModel(userId, systemPrompt, userContent);
            return normalizeLength(merged, userId);
        } catch (Exception e) {
            log.warn("Session summary merge failed: {}", e.getMessage());
            return normalize(existingSummary);
        }
    }

    public String compress(Long userId, String summary) {
        if (summary == null || summary.isBlank()) {
            return "";
        }
        if (summary.length() <= properties.getSoftMaxChars()) {
            return summary.trim();
        }
        try {
            String systemPrompt = """
                    你是会话摘要压缩器。将输入摘要压缩到更短，但不丢失关键信息。
                    必须保留所有未完成的约定、计划（含时间/地点/做什么）。
                    删除寒暄、重复、已完结的小事。不要编造。只输出压缩后的摘要正文。
                    """;
            String userContent = "请将以下摘要压缩到约 "
                    + properties.getTargetChars()
                    + " 字以内：\n\n"
                    + summary;
            String compressed = callModel(userId, systemPrompt, userContent);
            if (compressed == null || compressed.isBlank()) {
                return summary;
            }
            return compressed.trim();
        } catch (Exception e) {
            log.warn("Session summary compress failed: {}", e.getMessage());
            return summary;
        }
    }

    private String normalizeLength(String merged, Long userId) {
        if (merged == null || merged.isBlank()) {
            return "";
        }
        String text = merged.trim();
        if (text.length() > properties.getSoftMaxChars()) {
            text = compress(userId, text);
        }
        return enforceHardMax(text);
    }

    String enforceHardMax(String text) {
        if (text == null) {
            return "";
        }
        String trimmed = text.trim();
        if (trimmed.length() <= properties.getHardMaxChars()) {
            return trimmed;
        }
        String withoutStatus = dropSection(trimmed, "【用户状态】");
        if (withoutStatus.length() <= properties.getHardMaxChars()) {
            return withoutStatus;
        }
        String withoutTopics = dropSection(withoutStatus, "【正在聊的话题】");
        if (withoutTopics.length() <= properties.getHardMaxChars()) {
            return withoutTopics;
        }
        String plansOnly = extractSection(withoutTopics, "【约定/计划】");
        if (!plansOnly.isBlank() && plansOnly.length() <= properties.getHardMaxChars()) {
            return plansOnly;
        }
        if (trimmed.length() > properties.getHardMaxChars()) {
            return trimmed.substring(0, properties.getHardMaxChars());
        }
        return trimmed;
    }

    private String buildMergeSystemPrompt() {
        String structureHint = properties.isStructured()
                ? """
                输出格式（必须包含三个小节标题，无内容的小节可写「无」）：
                【约定/计划】
                【正在聊的话题】
                【用户状态】
                """
                : "";
        return """
                你是单聊会话摘要器。你不聊天，只维护一段「本次对话 Earlier 摘要」。
                
                任务：把「已有摘要」和「刚离开上下文窗口的新对话片段」合并成一份新摘要。
                
                必须保留：
                - 尚未完成的约定、计划（时间、地点、做什么）
                - 当前正在聊的话题
                - 用户最近的情绪/状态（若对后续回复有用）
                
                必须删除或压缩：
                - 寒暄、重复、纯表情
                - 已被用户明确推翻的内容（以片段里最新说法为准）
                
                禁止：
                - 编造未出现的信息
                - 输出长期人格事实（姓名爱好等留给别的系统）
                - markdown 代码块、JSON、解释文字
                
                """
                + structureHint
                + """
                
                长度：尽量控制在 """
                + properties.getTargetChars()
                + " 字以内，中文，简洁。";
    }

    private String buildMergeUserContent(String existingSummary, List<Message> pendingMessages) {
        String existing = existingSummary == null || existingSummary.isBlank() ? "（暂无）" : existingSummary.trim();
        return "【已有摘要】\n"
                + existing
                + "\n\n【刚离开窗口的对话片段】\n"
                + formatMessages(pendingMessages)
                + "\n\n请输出合并后的完整摘要（仅正文）：";
    }

    private String formatMessages(List<Message> messages) {
        List<String> lines = new ArrayList<>();
        for (Message msg : messages) {
            if (msg.getContent() == null || msg.getContent().isBlank()) {
                continue;
            }
            String role = "USER".equalsIgnoreCase(msg.getRole()) ? "用户" : "助手";
            String content = msg.getContent().trim();
            if (content.length() > 220) {
                content = content.substring(0, 220) + "...";
            }
            lines.add(role + "：" + content);
        }
        return lines.isEmpty() ? "（无有效内容）" : String.join("\n", lines);
    }

    private String callModel(Long userId, String systemPrompt, String userContent) {
        AiChatRequest request = new AiChatRequest();
        request.setProvider(AiConstants.PLATFORM_PROVIDER);
        request.setModel(properties.getModel());
        request.setTemperature(0.1);
        List<MessageDto> dtos = new ArrayList<>();
        MessageDto system = new MessageDto();
        system.setRole("system");
        system.setContent(systemPrompt);
        dtos.add(system);
        MessageDto user = new MessageDto();
        user.setRole("user");
        user.setContent(userContent);
        dtos.add(user);
        request.setMessages(dtos);
        ChatResult result = aiChatService.chatBlocking(userId, request);
        if (result == null || result.getContent() == null) {
            return "";
        }
        return result.getContent().trim();
    }

    private static String normalize(String text) {
        return text == null ? "" : text.trim();
    }

    private static String dropSection(String text, String header) {
        int start = text.indexOf(header);
        if (start < 0) {
            return text.trim();
        }
        int next = findNextSectionStart(text, start + header.length());
        String before = text.substring(0, start).trim();
        String after = next >= 0 ? text.substring(next).trim() : "";
        if (before.isBlank()) {
            return after;
        }
        if (after.isBlank()) {
            return before;
        }
        return before + "\n\n" + after;
    }

    private static String extractSection(String text, String header) {
        int start = text.indexOf(header);
        if (start < 0) {
            return "";
        }
        int contentStart = start + header.length();
        int next = findNextSectionStart(text, contentStart);
        String body = next >= 0 ? text.substring(contentStart, next) : text.substring(contentStart);
        return header + body.trim();
    }

    private static int findNextSectionStart(String text, int fromIndex) {
        String[] headers = {"【约定/计划】", "【正在聊的话题】", "【用户状态】"};
        int next = -1;
        for (String header : headers) {
            int idx = text.indexOf(header, fromIndex);
            if (idx >= 0 && (next < 0 || idx < next)) {
                next = idx;
            }
        }
        return next;
    }
}
