package com.lianyu.service.conversation;

import com.lianyu.dao.entity.Message;
import com.lianyu.service.dto.MessageDto;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 会话摘要合并 / 压缩。
 *
 * <p><b>AI 逻辑已迁到 Python</b>：摘要的提示词与模型调用都在
 * `backend-agent` 的 `/agent/session-summary`，Java 侧只保留「何时需要摘要」
 * 这类产品规则（在 {@link SessionSummaryService} 里）。
 *
 * <p>agent 不可用时**不再调用模型**：退回「保留原摘要」的安全行为，
 * 而不是在 Java 里再造一份提示词。这是「Java 零 AI 逻辑」的落地方式。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SessionSummaryMerger {

    private final SessionSummaryProperties properties;

    /** AGENT 模式下摘要文案与模型调用交给 Python。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.lianyu.service.agent.SessionSummaryPort sessionSummaryPort;

    public String merge(Long userId, String provider, String model,
                        String existingSummary, List<Message> pendingMessages) {
        if (pendingMessages == null || pendingMessages.isEmpty()) {
            return normalize(existingSummary);
        }
        String merged = generate(userId, provider, model, "merge", existingSummary,
                pendingMessages, 0);
        // agent 不可用：保留原摘要（不编造、不重复写入）
        return enforceHardMax(normalize(merged != null ? merged : existingSummary));
    }

    public String compress(Long userId, String provider, String model, String summary) {
        if (summary == null || summary.isBlank()) {
            return "";
        }
        if (summary.length() <= properties.getSoftMaxChars()) {
            return summary.trim();
        }
        String compressed = generate(userId, provider, model, "compress", summary,
                List.of(), properties.getTargetChars());
        String result = (compressed == null || compressed.isBlank()) ? summary : compressed.trim();
        return enforceHardMax(result);
    }

    private String generate(Long userId, String provider, String model, String mode,
                            String existingSummary, List<Message> messages, int targetChars) {
        if (sessionSummaryPort == null) {
            log.debug("Session summary skipped: agent port unavailable");
            return null;
        }
        List<com.lianyu.service.agent.SessionSummaryPort.SummaryMessage> payload =
                messages.stream()
                        .filter(message -> message != null)
                        .map(message -> new com.lianyu.service.agent.SessionSummaryPort
                                .SummaryMessage(message.getRole(), message.getContent()))
                        .toList();
        try {
            return sessionSummaryPort.generate(userId, provider, model, mode,
                    existingSummary, payload, targetChars);
        } catch (Exception e) {
            log.warn("Session summary via agent failed: {}", e.toString());
            return null;
        }
    }

    private String normalize(String summary) {
        return summary == null ? "" : summary.trim();
    }
    /**
     * 硬上限裁剪：按优先级依次丢弃段落（用户状态 → 正在聊的话题 → 只留约定/计划）。
     *
     * <p>这是确定性的文本裁剪规则，不含任何模型调用，属于产品逻辑。
     */
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
        return withoutTopics;
    }

    private String dropSection(String text, String header) {
        int start = text.indexOf(header);
        if (start < 0) {
            return text;
        }
        int next = text.indexOf("【", start + header.length());
        String tail = next < 0 ? "" : text.substring(next);
        return (text.substring(0, start).trim() + (tail.isBlank() ? "" : "\n\n" + tail.trim())).trim();
    }
}