package com.lianyu.service;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 将 AI 一次输出按换行拆成多条聊天气泡（单聊 / 群聊共用）。
 * 模型只回复一次；程序端按行切分，每非空行一条气泡。
 */
@Component
public class AssistantReplySplitter {

    public List<String> split(String fullContent, int maxRepliesPerTurn) {
        if (fullContent == null || fullContent.isBlank()) {
            return List.of();
        }
        String normalized = fullContent.replace("\r\n", "\n").trim();

        List<String> pieces = Arrays.stream(normalized.split("\\n"))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .collect(Collectors.toCollection(ArrayList::new));

        if (pieces.isEmpty()) {
            pieces.add(normalized);
        }

        int limit = Math.max(1, maxRepliesPerTurn);
        if (pieces.size() > limit) {
            List<String> merged = new ArrayList<>(pieces.subList(0, limit - 1));
            String tail = String.join("\n", pieces.subList(limit - 1, pieces.size()));
            merged.add(tail.trim());
            return merged;
        }
        return pieces;
    }

    /** @deprecated 按行切分后不再需要合并段内软换行；保留供测试或外部引用 */
    public String collapseSoftLineBreaks(String text) {
        if (text == null || text.isBlank()) {
            return text == null ? "" : text.trim();
        }
        return text.replace("\r\n", "\n").trim();
    }
}
