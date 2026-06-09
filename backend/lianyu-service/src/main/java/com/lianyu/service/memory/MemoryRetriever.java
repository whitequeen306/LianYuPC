package com.lianyu.service.memory;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lianyu.dao.entity.MemoryMeta;
import com.lianyu.dao.enums.MemoryType;
import com.lianyu.dao.mapper.MemoryMetaMapper;
import com.lianyu.service.ai.RerankerService;
import cn.hutool.core.util.StrUtil;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryRetriever {

    private static final BigDecimal MIN_IMPORTANCE = new BigDecimal("0.50");

    private final RerankerService rerankerService;
    private final MemoryVectorStore memoryVectorStore;
    private final MemoryMetaMapper memoryMetaMapper;
    private final MemoryCacheService memoryCacheService;

    public static final int DEFAULT_TOOL_TOP_K = 5;

    private static final int SEARCH_CANDIDATE_COUNT = 20;
    private static final float SIMILARITY_THRESHOLD = 0.3f;
    private static final int LIKE_BOOST_LIMIT = 5;

    @Value("${lianyu.memory.retrieval.importance-threshold:0.5}")
    private double importanceThreshold;

    public String retrieveProfileContext(Long characterId, Long userId) {
        return retrieveProfileContext(characterId, userId, null);
    }

    /**
     * 结构化长期记忆，发消息前预注入 system prompt（路径 A）。
     */
    public String retrieveProfileContext(Long characterId, Long userId, String lastUserMessage) {
        List<MemoryMeta> metas = loadContextMemoryMetas(characterId, userId, lastUserMessage);
        String facts = joinByType(metas, MemoryType.FACT, "用户画像");
        String emotions = joinByType(metas, MemoryType.EMOTION, "近期情绪线索");
        String relations = joinByType(metas, MemoryType.RELATION, "关系事件");
        String rituals = joinByType(metas, MemoryType.RITUAL, "专属仪式");
        String result = Stream.of(facts, emotions, relations, rituals)
                .filter(s -> s != null && !s.isBlank())
                .collect(Collectors.joining("\n\n"));
        return result.isBlank() ? null : result;
    }

    private String joinByType(List<MemoryMeta> metas, MemoryType type, String label) {
        List<String> lines = metas.stream()
                .filter(m -> m.getMemoryType() == type && m.getSummary() != null && !m.getSummary().isBlank())
                .map(m -> "- " + m.getSummary())
                .distinct()
                .toList();
        if (lines.isEmpty()) {
            return null;
        }
        return "[" + label + "]\n" + String.join("\n", lines);
    }

    /**
     * Agentic 语义检索：由 memory_search Tool 调用（路径 B）。
     */
    public List<String> searchSemantic(Long characterId, Long userId, String query, int topK) {
        if (StrUtil.isBlank(query)) {
            return List.of();
        }
        int k = topK > 0 ? topK : DEFAULT_TOOL_TOP_K;
        return retrieveSemantic(characterId, userId, query.trim(), k);
    }

    private List<String> retrieveSemantic(Long characterId, Long userId, String query, int topK) {
        try {
            List<String> cached = memoryCacheService.getSemanticResults(userId, characterId, query);
            if (cached != null) {
                return cached.size() > topK ? cached.subList(0, topK) : cached;
            }

            List<String> docTexts = new ArrayList<>();
            try {
                List<MemoryVectorStore.VectorHit> hits = memoryVectorStore.search(
                        characterId,
                        userId,
                        query,
                        Math.max(SEARCH_CANDIDATE_COUNT, topK * 3),
                        SIMILARITY_THRESHOLD);
                for (MemoryVectorStore.VectorHit hit : hits) {
                    docTexts.add(hit.summary());
                }
            } catch (Exception e) {
                log.warn("Vector search unavailable, falling back to recent memories: {}", e.getMessage());
            }

            if (docTexts.isEmpty()) {
                docTexts = loadRecentMemoryMetas(characterId, userId).stream()
                        .map(m -> m.getSummary() != null ? m.getSummary() : "")
                        .filter(s -> !s.isBlank())
                        .toList();
            }

            if (docTexts.isEmpty()) {
                return List.of();
            }

            List<RerankerService.ScoredDoc> reranked;
            try {
                reranked = rerankerService.rerank(query, docTexts, userId);
            } catch (Exception e) {
                log.warn("Rerank failed, using original order: {}", e.getMessage());
                reranked = new ArrayList<>();
                for (int i = 0; i < docTexts.size(); i++) {
                    reranked.add(new RerankerService.ScoredDoc(i, docTexts.get(i), 0.5f));
                }
            }

            List<String> summaries = reranked.stream()
                    .limit(topK)
                    .map(d -> "- " + d.text())
                    .toList();

            log.info("Memory retrieved: {} results for query ({} chars)", summaries.size(), query.length());
            memoryCacheService.putSemanticResults(userId, characterId, query, summaries);
            return summaries;
        } catch (Exception e) {
            log.error("Memory retrieval failed", e);
            return List.of();
        }
    }

    private List<MemoryMeta> loadContextMemoryMetas(Long characterId, Long userId, String lastUserMessage) {
        List<MemoryMeta> base = loadRecentMemoryMetas(characterId, userId);
        if (StrUtil.isBlank(lastUserMessage)) {
            return base;
        }
        List<MemoryMeta> boosted = loadLikeBoostedMetas(characterId, userId, lastUserMessage.trim());
        if (boosted.isEmpty()) {
            return base;
        }
        Map<Long, MemoryMeta> merged = new LinkedHashMap<>();
        for (MemoryMeta meta : base) {
            if (meta.getId() != null) {
                merged.put(meta.getId(), meta);
            }
        }
        for (MemoryMeta meta : boosted) {
            if (meta.getId() != null && !merged.containsKey(meta.getId())) {
                merged.put(meta.getId(), meta);
            }
        }
        return new ArrayList<>(merged.values());
    }

    private List<MemoryMeta> loadLikeBoostedMetas(Long characterId, Long userId, String lastUserMessage) {
        String keyword = pickLikeKeyword(lastUserMessage);
        if (keyword == null) {
            return List.of();
        }
        BigDecimal threshold = BigDecimal.valueOf(importanceThreshold).setScale(2, java.math.RoundingMode.HALF_UP);
        return memoryMetaMapper.selectList(
                new LambdaQueryWrapper<MemoryMeta>()
                        .eq(MemoryMeta::getCharacterId, characterId)
                        .eq(MemoryMeta::getUserId, userId)
                        .ge(MemoryMeta::getImportance, threshold)
                        .like(MemoryMeta::getSummary, keyword)
                        .orderByDesc(MemoryMeta::getImportance)
                        .orderByDesc(MemoryMeta::getCreatedAt)
                        .last("LIMIT " + LIKE_BOOST_LIMIT));
    }

    private String pickLikeKeyword(String text) {
        if (text.length() < 4) {
            return null;
        }
        String[] tokens = text.split("\\s+");
        for (String token : tokens) {
            String trimmed = token.trim();
            if (trimmed.length() >= 2 && trimmed.length() <= 12) {
                return trimmed;
            }
        }
        return text.length() > 12 ? text.substring(0, 12) : text;
    }

    private List<MemoryMeta> loadRecentMemoryMetas(Long characterId, Long userId) {
        List<CachedMemoryRow> cachedRows = memoryCacheService.getRecentRows(userId, characterId);
        if (cachedRows != null && !cachedRows.isEmpty()) {
            return cachedRows.stream().map(this::toMeta).toList();
        }

        BigDecimal threshold = BigDecimal.valueOf(importanceThreshold).setScale(2, java.math.RoundingMode.HALF_UP);
        List<MemoryMeta> fromDb = memoryMetaMapper.selectList(
                new LambdaQueryWrapper<MemoryMeta>()
                        .eq(MemoryMeta::getCharacterId, characterId)
                        .eq(MemoryMeta::getUserId, userId)
                        .ge(MemoryMeta::getImportance, threshold)
                        .orderByDesc(MemoryMeta::getImportance)
                        .orderByDesc(MemoryMeta::getCreatedAt)
                        .last("LIMIT " + SEARCH_CANDIDATE_COUNT));

        List<MemoryMeta> profileFacts = loadProfileFactMetas(characterId, userId);
        List<MemoryMeta> merged = mergeMetas(profileFacts, fromDb);

        List<CachedMemoryRow> rows = merged.stream().map(this::toCachedRow).toList();
        memoryCacheService.putRecentRows(userId, characterId, rows);
        return merged;
    }

    private List<MemoryMeta> loadProfileFactMetas(Long characterId, Long userId) {
        List<String> cached = memoryCacheService.getProfileFacts(userId, characterId);
        if (cached != null) {
            return cached.stream().map(line -> {
                MemoryMeta meta = new MemoryMeta();
                meta.setSummary(line.startsWith("- ") ? line.substring(2) : line);
                meta.setMemoryType(MemoryType.FACT);
                meta.setImportance(MIN_IMPORTANCE);
                return meta;
            }).toList();
        }

        List<MemoryMeta> metas = memoryMetaMapper.selectList(
                new LambdaQueryWrapper<MemoryMeta>()
                        .eq(MemoryMeta::getCharacterId, characterId)
                        .eq(MemoryMeta::getUserId, userId)
                        .likeRight(MemoryMeta::getSummary, "【长期记忆/")
                        .orderByDesc(MemoryMeta::getCreatedAt));

        Map<String, MemoryMeta> latestBySlot = new LinkedHashMap<>();
        for (MemoryMeta meta : metas) {
            String slot = extractProfileSlot(meta.getSummary());
            if (slot != null && !latestBySlot.containsKey(slot)) {
                latestBySlot.put(slot, meta);
            }
        }
        List<String> facts = latestBySlot.values().stream()
                .map(m -> "- " + m.getSummary())
                .toList();
        memoryCacheService.putProfileFacts(userId, characterId, facts);
        return new ArrayList<>(latestBySlot.values());
    }

    private List<MemoryMeta> mergeMetas(List<MemoryMeta> profileFacts, List<MemoryMeta> recent) {
        Set<Long> seen = new LinkedHashSet<>();
        List<MemoryMeta> merged = new ArrayList<>();
        for (MemoryMeta meta : profileFacts) {
            if (meta.getId() != null && seen.add(meta.getId())) {
                merged.add(meta);
            } else if (meta.getId() == null) {
                merged.add(meta);
            }
        }
        for (MemoryMeta meta : recent) {
            if (meta.getId() == null || seen.add(meta.getId())) {
                merged.add(meta);
            }
        }
        return merged;
    }

    private String extractProfileSlot(String summary) {
        if (!summary.startsWith("【长期记忆/")) {
            return null;
        }
        int end = summary.indexOf('】');
        if (end <= "【长期记忆/".length()) {
            return null;
        }
        return summary.substring("【长期记忆/".length(), end);
    }

    private MemoryMeta toMeta(CachedMemoryRow row) {
        MemoryMeta meta = new MemoryMeta();
        meta.setSummary(row.getSummary());
        meta.setMemoryType(row.getMemoryType());
        meta.setImportance(row.getImportance());
        return meta;
    }

    private CachedMemoryRow toCachedRow(MemoryMeta meta) {
        CachedMemoryRow row = new CachedMemoryRow();
        row.setSummary(meta.getSummary());
        row.setMemoryType(meta.getMemoryType());
        row.setImportance(meta.getImportance());
        return row;
    }
}
