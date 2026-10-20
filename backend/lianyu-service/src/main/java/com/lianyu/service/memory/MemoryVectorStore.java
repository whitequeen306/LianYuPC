package com.lianyu.service.memory;

import io.milvus.client.MilvusServiceClient;
import io.milvus.param.dml.DeleteParam;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Milvus 向量的**删除**入口（角色删除时清理遗留向量）。
 *
 * <p>写入与检索已整体迁到 Python（`backend-agent` 的 memory 模块，pymilvus）：
 * 那边负责 embedding + 检索 + 摘要抽取，Java 侧不再持有任何向量化/重排调用。
 * 这里只剩删除 —— 它是纯 IO，不涉及模型，属于「事务/持久化」这条 seam。
 *
 * <p>历史背景：本类原有 `insert` / `search`，依赖 `EmbeddingService`（DashScope
 * text-embedding）与 `RerankerService`（gte-rerank）。两者在新链路上都已无人调用，
 * 随本次清理一并删除 —— 否则 strict 检查器扫不到它们，Java 会一直「看起来零 AI 逻辑」。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MemoryVectorStore {

    private final MilvusServiceClient milvusClient;

    @Value("${lianyu.memory.milvus.collection:memory_vectors_v2}")
    private String collectionName;

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
}
