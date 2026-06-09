package com.lianyu.service.memory;

import com.lianyu.common.util.UserInputSanitizer;
import com.lianyu.dao.entity.Message;
import com.lianyu.dao.enums.MemoryType;
import com.lianyu.service.memory.MemoryExtractionHeuristics.HeuristicMemory;
import com.lianyu.service.memory.MemoryWriter.MemorySummaryTask;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryExtractionService {

    private static final int MAX_SUMMARY_LENGTH = 120;

    private final MemoryExtractionHeuristics heuristics;
    private final MemoryLlmExtractor llmExtractor;

    @Value("${lianyu.memory.extraction.enabled:true}")
    private boolean extractionEnabled;

    @Value("${lianyu.memory.extraction.importance-threshold:0.5}")
    private double importanceThreshold;

    @Value("${lianyu.memory.extraction.max-memories-per-turn:3}")
    private int maxMemoriesPerTurn;

    public List<ExtractedMemory> extract(List<Message> messages, MemorySummaryTask task) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }

        List<HeuristicMemory> heuristicMemories = heuristics.extractAll(messages);
        List<ExtractedMemory> merged = new ArrayList<>();
        for (HeuristicMemory memory : heuristicMemories) {
            merged.add(toExtracted(memory));
        }

        if (extractionEnabled && shouldCallLlm(messages, heuristicMemories)) {
            List<ExtractedMemory> llmMemories = llmExtractor.extract(task.userId(), messages);
            for (ExtractedMemory memory : llmMemories) {
                if (!isDuplicateSummary(merged, memory.summary())) {
                    merged.add(memory);
                }
            }
        }

        return applyGates(merged);
    }

    private boolean shouldCallLlm(List<Message> messages, List<HeuristicMemory> heuristicMemories) {
        if (heuristicMemories.isEmpty()) {
            return hasEligibleUserMessage(messages);
        }
        Set<Long> covered = heuristics.coveredMessageIds(heuristicMemories);
        for (Message msg : messages) {
            if (!"USER".equalsIgnoreCase(msg.getRole()) || msg.getContent() == null) {
                continue;
            }
            String text = msg.getContent().trim();
            if (!isEligibleUserText(text)) {
                continue;
            }
            if (!covered.contains(msg.getId())) {
                return true;
            }
        }
        return false;
    }

    private boolean hasEligibleUserMessage(List<Message> messages) {
        for (Message msg : messages) {
            if ("USER".equalsIgnoreCase(msg.getRole()) && isEligibleUserText(msg.getContent())) {
                return true;
            }
        }
        return false;
    }

    private boolean isEligibleUserText(String text) {
        if (text == null) {
            return false;
        }
        String trimmed = text.trim();
        if (trimmed.length() < 3) {
            return false;
        }
        if (trimmed.endsWith("?") || trimmed.endsWith("？")) {
            return false;
        }
        String stripped = trimmed.replaceAll("[\\p{So}\\p{Sk}\\s\\p{Punct}]+", "");
        return stripped.length() >= 2;
    }

    private List<ExtractedMemory> applyGates(List<ExtractedMemory> candidates) {
        Map<String, ExtractedMemory> deduped = new LinkedHashMap<>();
        for (ExtractedMemory candidate : candidates) {
            ExtractedMemory gated = gateOne(candidate);
            if (gated == null) {
                continue;
            }
            String key = gated.summary().toLowerCase(Locale.ROOT);
            ExtractedMemory existing = deduped.get(key);
            if (existing == null || gated.importance() > existing.importance()) {
                deduped.put(key, gated);
            }
        }
        List<ExtractedMemory> sorted = new ArrayList<>(deduped.values());
        sorted.sort((a, b) -> Double.compare(b.importance(), a.importance()));
        if (sorted.size() > maxMemoriesPerTurn) {
            return sorted.subList(0, maxMemoriesPerTurn);
        }
        return sorted;
    }

    private ExtractedMemory gateOne(ExtractedMemory candidate) {
        if (candidate == null || candidate.summary() == null) {
            return null;
        }
        String sanitized = UserInputSanitizer.sanitizeGenerationDescription(candidate.summary().trim());
        if (sanitized.isBlank()) {
            return null;
        }
        if (sanitized.length() > MAX_SUMMARY_LENGTH) {
            sanitized = sanitized.substring(0, MAX_SUMMARY_LENGTH);
        }
        if (candidate.importance() < importanceThreshold) {
            return null;
        }
        MemoryType type = candidate.memoryType() != null ? candidate.memoryType() : MemoryType.FACT;
        if (sanitized.startsWith("【长期记忆/")) {
            int end = sanitized.indexOf('】');
            if (end > 0 && end + 1 < sanitized.length()) {
                String value = sanitized.substring(end + 1).trim();
                if (!heuristics.isValidFactValue(sanitized, value)) {
                    return null;
                }
            }
        }
        return new ExtractedMemory(sanitized, type, candidate.sourceMsgId(), candidate.importance());
    }

    private ExtractedMemory toExtracted(HeuristicMemory memory) {
        return new ExtractedMemory(
                memory.summary(),
                memory.memoryType(),
                memory.sourceMsgId(),
                memory.importance());
    }

    private boolean isDuplicateSummary(List<ExtractedMemory> existing, String summary) {
        if (summary == null) {
            return true;
        }
        String key = summary.trim().toLowerCase(Locale.ROOT);
        for (ExtractedMemory memory : existing) {
            if (memory.summary() != null
                    && memory.summary().trim().toLowerCase(Locale.ROOT).equals(key)) {
                return true;
            }
        }
        return false;
    }
}
