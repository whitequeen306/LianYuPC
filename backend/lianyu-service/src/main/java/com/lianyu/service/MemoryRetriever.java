package com.lianyu.service;

import com.lianyu.dao.entity.MemoryMeta;
import com.lianyu.dao.mapper.MemoryMetaMapper;
import com.lianyu.storage.milvus.MilvusConfig;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.SearchResultData;
import io.milvus.param.MetricType;
import io.milvus.param.dml.SearchParam;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryRetriever {

    private final EmbeddingService embeddingService;
    private final RerankerService rerankerService;
    private final MilvusServiceClient milvusClient;
    private final MemoryMetaMapper memoryMetaMapper;

    private static final int DEFAULT_TOP_K = 5;
    private static final int SEARCH_CANDIDATE_COUNT = 20;
    private static final float SIMILARITY_THRESHOLD = 0.3f;

    public String retrieveContext(Long characterId, Long userId, String query) {
        // 结构化资料（姓名/爱好等）每次对话都注入，避免仅依赖「最近 N 条」里的旧自称
        List<String> memories = new ArrayList<>(loadProfileFacts(characterId, userId));

        if (isMemoryWorthyQuery(query)) {
            for (String item : retrieveSemantic(characterId, userId, query, DEFAULT_TOP_K)) {
                if (!memories.contains(item)) {
                    memories.add(item);
                }
            }
        }

        if (memories.isEmpty()) {
            return null;
        }
        return String.join("\n", memories);
    }

    /**
     * 结构化长期记忆（姓名/爱好等），在涉及用户身份或爱好的问句时加载。
     */
    private List<String> loadProfileFacts(Long characterId, Long userId) {
        List<MemoryMeta> metas = memoryMetaMapper.selectList(
                new LambdaQueryWrapper<MemoryMeta>()
                        .eq(MemoryMeta::getCharacterId, characterId)
                        .eq(MemoryMeta::getUserId, userId)
                        .likeRight(MemoryMeta::getSummary, "【长期记忆/")
                        .orderByDesc(MemoryMeta::getCreatedAt));

        Map<String, String> latestBySlot = new LinkedHashMap<>();
        for (MemoryMeta meta : metas) {
            if (meta.getSummary() == null || meta.getSummary().isBlank()) {
                continue;
            }
            String slot = extractProfileSlot(meta.getSummary());
            if (slot != null && !latestBySlot.containsKey(slot)) {
                latestBySlot.put(slot, "- " + meta.getSummary());
            }
        }
        return new ArrayList<>(latestBySlot.values());
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

    private List<String> retrieveSemantic(Long characterId, Long userId, String query, int topK) {
        try {
            List<MemoryMeta> candidates = new ArrayList<>();

            // Try vector search first
            try {
                float[] vec = embeddingService.embed(query, userId);

                List<Float> queryVector = new ArrayList<>(vec.length);
                for (float f : vec) {
                    queryVector.add(f);
                }

                SearchParam searchParam = SearchParam.newBuilder()
                        .withCollectionName(MilvusConfig.COLLECTION_MEMORY_VECTORS)
                        .withVectorFieldName("vector")
                        .withVectors(List.of(queryVector))
                        .withOutFields(List.of("character_id", "user_id"))
                        .withTopK(Math.max(SEARCH_CANDIDATE_COUNT, topK * 3))
                        .withMetricType(MetricType.COSINE)
                        .withExpr("character_id == " + characterId + " && user_id == " + userId)
                        .build();

                var searchResult = milvusClient.search(searchParam);
                if (searchResult.getData() == null) {
                    log.warn("Vector search returned null data, status={}", searchResult.getStatus());
                    throw new RuntimeException("Vector search returned null");
                }
                SearchResultData resultData = searchResult.getData().getResults();

                if (resultData.getIds().getIntId().getDataCount() > 0) {
                    List<Long> vecIds = new ArrayList<>();
                    for (int i = 0; i < resultData.getIds().getIntId().getDataCount(); i++) {
                        long vecId = resultData.getIds().getIntId().getData(i);
                        float score = resultData.getScores(i);
                        if (score >= SIMILARITY_THRESHOLD) {
                            vecIds.add(vecId);
                        }
                    }

                    if (!vecIds.isEmpty()) {
                        for (Long vecId : vecIds) {
                            MemoryMeta meta = memoryMetaMapper.selectOne(
                                    new LambdaQueryWrapper<MemoryMeta>()
                                            .eq(MemoryMeta::getMilvusVecId, String.valueOf(vecId))
                                            .eq(MemoryMeta::getCharacterId, characterId)
                                            .eq(MemoryMeta::getUserId, userId)
                                            .last("LIMIT 1"));
                            if (meta != null && meta.getSummary() != null) {
                                candidates.add(meta);
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("Vector search unavailable, falling back to recent memories: {}", e.getMessage());
            }

            // Fallback: also fetch recent memories without vectors
            if (candidates.isEmpty()) {
                List<MemoryMeta> fallback = memoryMetaMapper.selectList(
                        new LambdaQueryWrapper<MemoryMeta>()
                                .eq(MemoryMeta::getCharacterId, characterId)
                                .eq(MemoryMeta::getUserId, userId)
                                .orderByDesc(MemoryMeta::getCreatedAt)
                                .last("LIMIT " + SEARCH_CANDIDATE_COUNT));
                candidates.addAll(fallback);
            }

            if (candidates.isEmpty()) {
                return List.of();
            }

            // Rerank with query
            List<String> docTexts = candidates.stream()
                    .map(m -> m.getSummary() != null ? m.getSummary() : "")
                    .toList();

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
            return summaries;
        } catch (Exception e) {
            log.error("Memory retrieval failed", e);
            return List.of();
        }
    }

    /**
     * 过滤掉寒暄类短句，避免「你好」「在吗」这类请求触发向量检索与重排。
     */
    private boolean isMemoryWorthyQuery(String query) {
        if (query == null) {
            return false;
        }
        String q = query.trim();
        if (q.isEmpty()) {
            return false;
        }
        String normalized = q.replaceAll("\\s+", "");
        if (normalized.length() <= 2) {
            return false;
        }
        String lower = normalized.toLowerCase();
        if (lower.matches("^(你好|您好|哈喽|hello|hi|早上好|下午好|晚上好|在吗|在不在|谢谢|拜拜|好的|ok|嗯+|哦+|嗨)[!！?？~～啊呀嘛呢]*$")) {
            return false;
        }
        String[] trivial = {
                "你好", "哈喽", "hello", "hi", "早上好", "下午好", "晚上好",
                "在吗", "在不在", "收到", "好的", "ok", "嗯", "哦", "谢谢", "拜拜"
        };
        for (String t : trivial) {
            if (lower.equals(t)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 涉及用户身份、名字、爱好的问句：加载结构化长期记忆（不走向量检索也可命中）。
     */
    private boolean isProfileRelatedQuery(String query) {
        if (query == null) {
            return false;
        }
        String q = query.trim().replaceAll("\\s+", "");
        if (q.isEmpty()) {
            return false;
        }
        return q.matches(".*(叫什么|名字|是谁|我是谁|你知道我|记得我|还记得|我叫什么|我的名|喜欢什么|爱吃什么|忌口|过敏).*");
    }

    public List<String> retrieve(Long characterId, Long userId, String query, int topK) {
        if (!isMemoryWorthyQuery(query)) {
            return List.of();
        }
        return retrieveSemantic(characterId, userId, query, topK);
    }
}
