package com.lianyu.service.memory;

import com.lianyu.dao.enums.MemoryType;
import com.lianyu.service.ai.EmbeddingService;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.FieldData;
import io.milvus.grpc.MutationResult;
import io.milvus.grpc.SearchResultData;
import io.milvus.param.dml.DeleteParam;
import io.milvus.param.dml.InsertParam;
import io.milvus.param.dml.SearchParam;
import io.milvus.param.MetricType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class MemoryVectorStore {

    private final MilvusServiceClient milvusClient;
    private final EmbeddingService embeddingService;

    @Value("${lianyu.memory.milvus.collection:memory_vectors_v2}")
    private String collectionName;

    public record VectorHit(String summary, float score) {}

    public String insert(Long characterId,
                         Long userId,
                         Long memoryId,
                         String summary,
                         MemoryType memoryType) {
        if (summary == null || summary.isBlank()) {
            return null;
        }
        try {
            float[] vector = embeddingService.embed(summary, userId);
            List<Float> floatList = new ArrayList<>(vector.length);
            for (float f : vector) {
                floatList.add(f);
            }

            String memoryTypeName = memoryType != null ? memoryType.name() : MemoryType.FACT.name();
            String clippedSummary = summary.length() > 512 ? summary.substring(0, 512) : summary;

            List<InsertParam.Field> fields = List.of(
                    new InsertParam.Field("character_id", List.of(characterId)),
                    new InsertParam.Field("user_id", List.of(userId)),
                    new InsertParam.Field("memory_id", List.of(memoryId != null ? memoryId : 0L)),
                    new InsertParam.Field("summary", List.of(clippedSummary)),
                    new InsertParam.Field("memory_type", List.of(memoryTypeName)),
                    new InsertParam.Field("vector", List.of(floatList))
            );

            InsertParam insertParam = InsertParam.newBuilder()
                    .withCollectionName(collectionName)
                    .withFields(fields)
                    .build();

            MutationResult mr = milvusClient.insert(insertParam).getData();
            if (mr != null && mr.getIDs().getIntId().getDataCount() > 0) {
                return String.valueOf(mr.getIDs().getIntId().getData(0));
            }
        } catch (Exception e) {
            log.warn("Milvus insert failed collection={}: {}", collectionName, e.getMessage());
        }
        return null;
    }

    public void delete(List<String> vectorIds) {
        if (vectorIds == null || vectorIds.isEmpty()) {
            return;
        }
        List<String> ids = vectorIds.stream()
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .toList();
        if (ids.isEmpty()) {
            return;
        }
        try {
            String expr = ids.stream()
                    .map(id -> "id == " + id)
                    .reduce((left, right) -> left + " or " + right)
                    .orElse(null);
            if (expr == null || expr.isBlank()) {
                return;
            }
            milvusClient.delete(DeleteParam.newBuilder()
                    .withCollectionName(collectionName)
                    .withExpr(expr)
                    .build());
        } catch (Exception e) {
            log.warn("Milvus delete failed collection={}: {}", collectionName, e.getMessage());
        }
    }

    public List<VectorHit> search(Long characterId,
                                  Long userId,
                                  String query,
                                  int topK,
                                  float similarityThreshold) {
        float[] vec = embeddingService.embed(query, userId);
        List<Float> queryVector = new ArrayList<>(vec.length);
        for (float f : vec) {
            queryVector.add(f);
        }

        SearchParam searchParam = SearchParam.newBuilder()
                .withCollectionName(collectionName)
                .withVectorFieldName("vector")
                .withVectors(List.of(queryVector))
                .withOutFields(List.of("summary", "memory_type", "memory_id"))
                .withTopK(topK)
                .withMetricType(MetricType.COSINE)
                .withExpr("character_id == " + characterId + " && user_id == " + userId)
                .build();

        var searchResult = milvusClient.search(searchParam);
        if (searchResult.getData() == null) {
            throw new RuntimeException("Vector search returned null");
        }
        SearchResultData resultData = searchResult.getData().getResults();
        if (resultData.getIds().getIntId().getDataCount() == 0) {
            return List.of();
        }

        Map<Integer, String> summaryByIndex = extractStringField(resultData, "summary");
        List<VectorHit> hits = new ArrayList<>();
        for (int i = 0; i < resultData.getIds().getIntId().getDataCount(); i++) {
            float score = resultData.getScores(i);
            if (score < similarityThreshold) {
                continue;
            }
            String summary = summaryByIndex.get(i);
            if (summary != null && !summary.isBlank()) {
                hits.add(new VectorHit(summary, score));
            }
        }
        return hits;
    }

    private Map<Integer, String> extractStringField(SearchResultData resultData, String fieldName) {
        Map<Integer, String> map = new HashMap<>();
        for (FieldData field : resultData.getFieldsDataList()) {
            if (!fieldName.equals(field.getFieldName())) {
                continue;
            }
            if (field.getScalars().hasStringData()) {
                List<String> values = field.getScalars().getStringData().getDataList();
                for (int i = 0; i < values.size(); i++) {
                    map.put(i, values.get(i));
                }
            }
            break;
        }
        return map;
    }
}
