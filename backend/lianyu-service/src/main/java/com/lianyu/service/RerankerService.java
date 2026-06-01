package com.lianyu.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lianyu.ai.config.AiConfig;
import com.lianyu.service.dto.VaultEntryResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class RerankerService {

    private final AiConfig aiConfig;
    private final ApiKeyVaultService vaultService;
    private final ObjectMapper objectMapper;

    public RerankerService(AiConfig aiConfig, ApiKeyVaultService vaultService, ObjectMapper objectMapper) {
        this.aiConfig = aiConfig;
        this.vaultService = vaultService;
        this.objectMapper = objectMapper;
    }

    public List<ScoredDoc> rerank(String query, List<String> documents) {
        return rerank(query, documents, null);
    }

    public List<ScoredDoc> rerank(String query, List<String> documents, Long userId) {
        if (documents == null || documents.isEmpty()) {
            return List.of();
        }

        // Try user's vault first, fall back to global config
        String apiKey;
        String url;
        String model;

        if (userId != null) {
            VaultEntryResponse vault = findRerankerProvider(userId);
            if (vault != null) {
                apiKey = vault.getApiKey();
                String baseUrl = vault.getBaseUrl();
                model = vault.getModelDefault() != null ? vault.getModelDefault() : "gte-rerank-v2";
                // Use AliCloud DashScope reranker format for now
                url = buildRerankUrl(baseUrl);
                return callRerankApi(apiKey, url, model, query, documents);
            }
        }

        // Fall back to global config
        AiConfig.Reranker cfg = aiConfig.getReranker();
        return callRerankApi(cfg.getApiKey(), cfg.getUrl(), cfg.getModel(), query, documents);
    }

    private VaultEntryResponse findRerankerProvider(Long userId) {
        List<VaultEntryResponse> vaults = vaultService.list(userId);
        // Use the first non-ollama provider for reranking (ollama doesn't support reranking)
        for (VaultEntryResponse v : vaults) {
            if (!"ollama".equalsIgnoreCase(v.getProvider())) {
                return v;
            }
        }
        return null;
    }

    private String buildRerankUrl(String baseUrl) {
        // For now, reranker uses AliCloud DashScope format.
        // OpenAI-compatible providers typically don't have reranker endpoints.
        // This can be extended later for Cohere/Jina reranker APIs.
        return "https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank";
    }

    private List<ScoredDoc> callRerankApi(String apiKey, String url, String model,
                                           String query, List<String> documents) {
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("No reranker API key configured, returning original order");
            List<ScoredDoc> fallback = new ArrayList<>();
            for (int i = 0; i < documents.size(); i++) {
                fallback.add(new ScoredDoc(i, documents.get(i), 0.5f));
            }
            return fallback;
        }

        try {
            Map<String, Object> body = Map.of(
                    "model", model,
                    "input", Map.of(
                            "query", query,
                            "documents", documents
                    ),
                    "parameters", Map.of(
                            "top_n", Math.min(documents.size(), 10),
                            "return_documents", false
                    )
            );

            RestClient client = RestClient.create();
            String response = client.post()
                    .uri(url)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .body(body)
                    .retrieve()
                    .body(String.class);

            JsonNode root = objectMapper.readTree(response);
            JsonNode results = root.path("output").path("results");

            List<ScoredDoc> scoredDocs = new ArrayList<>();
            for (JsonNode r : results) {
                int index = r.path("index").asInt();
                float score = r.path("relevance_score").floatValue();
                if (index < documents.size()) {
                    scoredDocs.add(new ScoredDoc(index, documents.get(index), score));
                }
            }

            scoredDocs.sort(Comparator.comparing(ScoredDoc::score).reversed());
            log.info("Reranked {} documents to {} results", documents.size(), scoredDocs.size());
            return scoredDocs;
        } catch (Exception e) {
            log.warn("Rerank failed, returning original order: {}", e.getMessage());
            List<ScoredDoc> fallback = new ArrayList<>();
            for (int i = 0; i < documents.size(); i++) {
                fallback.add(new ScoredDoc(i, documents.get(i), 0.5f));
            }
            return fallback;
        }
    }

    public record ScoredDoc(int index, String text, float score) {}
}
