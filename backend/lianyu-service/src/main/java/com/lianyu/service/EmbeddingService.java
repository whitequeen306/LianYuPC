package com.lianyu.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lianyu.ai.config.AiConfig;
import com.lianyu.common.base.ErrorCode;
import com.lianyu.common.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.Map;

@Slf4j
@Service
public class EmbeddingService {

    private final AiConfig aiConfig;
    private final ObjectMapper objectMapper;

    public EmbeddingService(AiConfig aiConfig, ObjectMapper objectMapper) {
        this.aiConfig = aiConfig;
        this.objectMapper = objectMapper;
    }

    public float[] embed(String text) {
        return embed(text, null);
    }

    public float[] embed(String text, Long userId) {
        // 向量服务统一走全局配置（阿里云 DashScope + text-embedding-v3）
        // 避免用户对话 provider/modelDefault 影响向量化模型，导致 model_not_found。
        AiConfig.Embedding cfg = aiConfig.getEmbedding();
        return callEmbeddingApi(cfg.getApiKey(), cfg.getBaseUrl(), cfg.getModel(), text);
    }

    private float[] callEmbeddingApi(String apiKey, String baseUrl, String model, String text) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new BusinessException(ErrorCode.AI_PROVIDER_ERROR, "未配置 Embedding 服务");
        }

        try {
            String base = baseUrl;
            if (base == null || base.isBlank()) {
                base = "https://api.openai.com/v1";
            }
            if (base.endsWith("/")) {
                base = base.substring(0, base.length() - 1);
            }
            if (!base.endsWith("/v1") && !base.contains("/v1/")) {
                if (!base.endsWith("/embeddings")) {
                    base = base + "/v1";
                }
            }
            String url = base.endsWith("/v1") ? base + "/embeddings" : base + "/embeddings";

            Map<String, Object> body = Map.of(
                    "model", model,
                    "input", text
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
            JsonNode data = root.path("data");
            if (data.isArray() && !data.isEmpty()) {
                JsonNode embedding = data.get(0).path("embedding");
                float[] vec = new float[embedding.size()];
                for (int i = 0; i < embedding.size(); i++) {
                    vec[i] = embedding.get(i).floatValue();
                }
                return vec;
            }
            throw new BusinessException(ErrorCode.AI_PROVIDER_ERROR, "Embedding 返回为空");
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            // 向量服务在部分 provider 上可能不可用，避免刷整段堆栈污染主链路日志
            log.warn("Embedding unavailable: {}", e.getMessage());
            throw new BusinessException(ErrorCode.AI_PROVIDER_ERROR, "向量化失败: " + e.getMessage());
        }
    }
}
