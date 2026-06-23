package com.lianyu.web.controller;

import com.lianyu.service.storage.FileStorageService;
import io.minio.GetObjectResponse;
import io.minio.StatObjectResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.io.InputStream;

@Slf4j
@Tag(name = "PublicFile", description = "公开静态文件（头像等）")
@RestController
@RequiredArgsConstructor
public class PublicFileController {

    private final FileStorageService fileStorageService;

    @Operation(summary = "读取 MinIO 中的公开文件（头像）")
    @GetMapping("/api/public/files/**")
    public ResponseEntity<StreamingResponseBody> serveFile(HttpServletRequest request) {
        String objectKey = extractObjectKey(request.getRequestURI());
        if (!StringUtils.hasText(objectKey)) {
            return ResponseEntity.notFound().build();
        }

        try {
            StatObjectResponse stat = fileStorageService.statObject(objectKey);
            String contentType = fileStorageService.resolveSafePublicContentType(objectKey, stat.contentType());
            if (fileStorageService.isDangerousPublicContentType(stat.contentType())) {
                log.warn("Blocked dangerous public file content-type: key={}, type={}", objectKey, stat.contentType());
                return ResponseEntity.notFound().build();
            }

            StreamingResponseBody body = outputStream -> {
                try (GetObjectResponse object = fileStorageService.getObject(objectKey);
                     InputStream in = object) {
                    in.transferTo(outputStream);
                } catch (Exception e) {
                    throw new IOException("Failed to stream object: " + objectKey, e);
                }
            };

            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_TYPE, contentType)
                    .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                    .header("X-Content-Type-Options", "nosniff")
                    .header(HttpHeaders.CACHE_CONTROL, cacheControlFor(objectKey))
                    .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(stat.size()))
                    .body(body);
        } catch (Exception e) {
            log.warn("Public file not found: {}", objectKey);
            return ResponseEntity.notFound().build();
        }
    }

    private String extractObjectKey(String requestUri) {
        String prefix = FileStorageService.PUBLIC_FILE_PREFIX;
        int idx = requestUri.indexOf(prefix);
        if (idx < 0) {
            return null;
        }
        return requestUri.substring(idx + prefix.length());
    }

    private static String cacheControlFor(String objectKey) {
        if (objectKey != null && objectKey.startsWith("square-avatars-thumb/")) {
            return "public, max-age=31536000, immutable";
        }
        return "public, max-age=86400";
    }
}
