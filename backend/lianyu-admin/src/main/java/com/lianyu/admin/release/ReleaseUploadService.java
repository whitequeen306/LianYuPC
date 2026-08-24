package com.lianyu.admin.release;

import com.lianyu.admin.identity.AdminAuthorizationService;
import com.lianyu.storage.minio.MinioConfig;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.http.Method;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class ReleaseUploadService {
    private final io.minio.MinioClient minioClient;
    private final MinioConfig minioConfig;
    private final AdminAuthorizationService authorization;
    public Map<String,Object> createUpload(String fileName, long size) {
        authorization.require("release.manage");
        if (fileName == null || fileName.contains("..") || fileName.contains("/") || fileName.contains("\\")) throw new IllegalArgumentException("文件名无效");
        if (size <= 0 || size > 1024L * 1024L * 1024L) throw new IllegalArgumentException("文件大小超出限制");
        String object = "admin/staging/" + UUID.randomUUID() + "/" + fileName;
        try {
            String url = minioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder().method(Method.PUT).bucket(minioConfig.getBucket()).object(object).expiry(15, TimeUnit.MINUTES).build());
            return Map.of("objectKey", object, "uploadUrl", url, "expiresInSeconds", 900, "maxBytes", 1024L * 1024L * 1024L);
        } catch (Exception e) { throw new IllegalStateException("上传地址生成失败", e); }
    }
}
