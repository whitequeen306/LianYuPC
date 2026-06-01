package com.lianyu.storage.milvus;

import io.milvus.client.MilvusServiceClient;
import io.milvus.param.ConnectParam;
import io.milvus.param.IndexType;
import io.milvus.param.MetricType;
import io.milvus.param.collection.CreateCollectionParam;
import io.milvus.param.collection.FieldType;
import io.milvus.param.collection.HasCollectionParam;
import io.milvus.param.collection.LoadCollectionParam;
import io.milvus.param.index.CreateIndexParam;
import lombok.Data;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;

@Slf4j
@Data
@Configuration
@ConfigurationProperties(prefix = "milvus")
@ConditionalOnProperty(name = "lianyu.storage.milvus.enabled", havingValue = "true", matchIfMissing = true)
public class MilvusConfig {
    private String host = "localhost";
    private int port = 19530;
    private String database = "default";
    private int dim = 1024;

    public static final String COLLECTION_MEMORY_VECTORS = "memory_vectors";

    @Bean
    @org.springframework.context.annotation.Lazy
    public MilvusServiceClient milvusClient() {
        return new MilvusServiceClient(
                ConnectParam.newBuilder()
                        .withHost(host)
                        .withPort(port)
                        .withDatabaseName(database)
                        .build()
        );
    }

    @EventListener(ApplicationReadyEvent.class)
    public void initCollection() {
        try {
            MilvusServiceClient client = milvusClient();
            HasCollectionParam hasParam = HasCollectionParam.newBuilder()
                    .withCollectionName(COLLECTION_MEMORY_VECTORS)
                    .build();

            if (client.hasCollection(hasParam).getData()) {
                log.info("Milvus collection '{}' already exists, skipping creation", COLLECTION_MEMORY_VECTORS);
                client.loadCollection(LoadCollectionParam.newBuilder()
                        .withCollectionName(COLLECTION_MEMORY_VECTORS)
                        .build());
                log.info("Milvus collection '{}' loaded into memory", COLLECTION_MEMORY_VECTORS);
                return;
            }

            FieldType idField = FieldType.newBuilder()
                    .withName("id")
                    .withDataType(io.milvus.grpc.DataType.Int64)
                    .withPrimaryKey(true)
                    .withAutoID(true)
                    .build();

            FieldType charIdField = FieldType.newBuilder()
                    .withName("character_id")
                    .withDataType(io.milvus.grpc.DataType.Int64)
                    .build();

            FieldType userIdField = FieldType.newBuilder()
                    .withName("user_id")
                    .withDataType(io.milvus.grpc.DataType.Int64)
                    .build();

            FieldType vecField = FieldType.newBuilder()
                    .withName("vector")
                    .withDataType(io.milvus.grpc.DataType.FloatVector)
                    .withDimension(dim)
                    .build();

            CreateCollectionParam createParam = CreateCollectionParam.newBuilder()
                    .withCollectionName(COLLECTION_MEMORY_VECTORS)
                    .withFieldTypes(Arrays.asList(idField, charIdField, userIdField, vecField))
                    .build();

            client.createCollection(createParam);
            log.info("Milvus collection '{}' created (dim={})", COLLECTION_MEMORY_VECTORS, dim);

            CreateIndexParam indexParam = CreateIndexParam.newBuilder()
                    .withCollectionName(COLLECTION_MEMORY_VECTORS)
                    .withFieldName("vector")
                    .withIndexType(IndexType.IVF_FLAT)
                    .withMetricType(MetricType.COSINE)
                    .withExtraParam("{\"nlist\":128}")
                    .build();
            client.createIndex(indexParam);
            log.info("Milvus index created on 'vector' field");

            client.loadCollection(LoadCollectionParam.newBuilder()
                    .withCollectionName(COLLECTION_MEMORY_VECTORS)
                    .build());
            log.info("Milvus collection '{}' loaded into memory", COLLECTION_MEMORY_VECTORS);
        } catch (Exception e) {
            log.error("Milvus collection init failed", e);
        }
    }
}
