package com.lianyu.service.ai;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 助手回复统一入口：先规范化模型原文，再切分为多条气泡内容。
 */
@Component
@RequiredArgsConstructor
public class AssistantReplyService {

    private final AssistantReplySplitter replySplitter;

    public record ProcessedReply(String normalized, List<String> pieces) {
    }

    public ProcessedReply process(String raw, int maxRepliesPerTurn) {
        String normalized = AssistantContentNormalizer.normalize(raw);
        if (normalized.isBlank()) {
            return new ProcessedReply("", List.of());
        }
        List<String> pieces = replySplitter.split(normalized, maxRepliesPerTurn);
        return new ProcessedReply(normalized, pieces);
    }
}
