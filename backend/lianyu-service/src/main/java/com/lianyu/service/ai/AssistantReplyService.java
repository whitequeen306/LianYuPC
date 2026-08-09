package com.lianyu.service.ai;

import java.util.ArrayList;
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
        String prepared = AssistantContentNormalizer.prepareForSplit(raw);
        if (prepared.isBlank()) {
            return new ProcessedReply("", List.of());
        }
        List<String> split = replySplitter.split(prepared, maxRepliesPerTurn);
        List<String> pieces = new ArrayList<>(split.size());
        for (String piece : split) {
            String normalizedPiece = AssistantContentNormalizer.normalize(piece);
            if (!normalizedPiece.isBlank()) {
                pieces.add(normalizedPiece);
            }
        }
        if (pieces.isEmpty()) {
            return new ProcessedReply("", List.of());
        }
        // 流式 replace 用：每条气泡一行，便于前端按条替换
        return new ProcessedReply(String.join("\n", pieces), pieces);
    }
}
