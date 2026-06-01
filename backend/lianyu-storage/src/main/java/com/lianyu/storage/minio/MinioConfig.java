package com.lianyu.storage.minio;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import lombok.Data;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Data
@Configuration
@ConfigurationProperties(prefix = "minio")
@ConditionalOnProperty(name = "lianyu.storage.minio.enabled", havingValue = "true", matchIfMissing = true)
public class MinioConfig {
    private String endpoint;
    private String accessKey;
    private String secretKey;
    private String bucket = "lianyu";

    @Bean
    public MinioClient minioClient() {
        return MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .build();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void initBucket() {
        try {
            MinioClient client = minioClient();
            boolean exists = client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
            if (!exists) {
                client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                log.info("MinIO bucket '{}' created", bucket);
            } else {
                log.info("MinIO bucket '{}' already exists", bucket);
            }
        } catch (Exception e) {
            log.error("MinIO bucket init failed", e);
        }
    }
}
