package com.lianyu.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.lianyu.dao.entity.CharacterSquareTemplate;
import com.lianyu.dao.mapper.CharacterSquareTemplateMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 启动时将 classpath square-avatars/{slug}.* 同步到 MinIO，并写回模板 avatar_url（object key）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CharacterSquareAvatarSync implements ApplicationRunner {

    private final FileStorageService fileStorageService;
    private final CharacterSquareTemplateMapper templateMapper;

    @Override
    public void run(ApplicationArguments args) {
        Map<String, Resource> resources = loadSquareAvatarResources();
        if (resources.isEmpty()) {
            log.info("No square-avatars on classpath, skip MinIO sync");
            return;
        }

        List<String> slugs = resources.keySet().stream()
                .filter(CharacterSquareCatalog::isKnownSlug)
                .toList();
        Map<String, CharacterSquareTemplate> templateBySlug = loadTemplatesBySlug(slugs);

        int uploaded = 0;
        int skipped = 0;
        for (Map.Entry<String, Resource> entry : resources.entrySet()) {
            String slug = entry.getKey();
            if (!CharacterSquareCatalog.isKnownSlug(slug)) {
                continue;
            }
            try {
                int result = syncOne(slug, entry.getValue(), templateBySlug.get(slug));
                if (result > 0) {
                    uploaded += result;
                } else {
                    skipped++;
                }
            } catch (Exception e) {
                log.warn("Square avatar sync failed for slug={}: {}", slug, e.getMessage());
            }
        }
        log.info("Character square avatar sync finished: uploaded={}, skipped={}", uploaded, skipped);
    }

    private Map<String, CharacterSquareTemplate> loadTemplatesBySlug(List<String> slugs) {
        if (slugs == null || slugs.isEmpty()) {
            return Map.of();
        }
        List<CharacterSquareTemplate> rows = templateMapper.selectList(new LambdaQueryWrapper<CharacterSquareTemplate>()
                .in(CharacterSquareTemplate::getSlug, slugs));
        Map<String, CharacterSquareTemplate> map = new HashMap<>();
        for (CharacterSquareTemplate row : rows) {
            if (row.getSlug() != null) {
                map.put(row.getSlug(), row);
            }
        }
        return map;
    }

    /**
     * @return 1 表示本次写入了 MinIO（可能伴随 DB 更新），0 表示已存在跳过
     */
    private int syncOne(String slug, Resource resource, CharacterSquareTemplate existing) throws Exception {
        String filename = resource.getFilename();
        if (filename == null) {
            return 0;
        }
        String expectedKey = fileStorageService.resolveSquareAvatarObjectKey(slug, filename);
        String contentType = guessContentType(filename);
        byte[] bytes;
        try (InputStream in = resource.getInputStream()) {
            bytes = in.readAllBytes();
        }

        if (expectedKey != null && fileStorageService.objectExists(expectedKey)) {
            long remoteSize = fileStorageService.objectSize(expectedKey);
            boolean sameBytes = remoteSize >= 0 && remoteSize == bytes.length;
            boolean dbAligned = existing == null || expectedKey.equals(existing.getAvatarUrl());
            if (sameBytes && dbAligned) {
                return 0;
            }
            if (sameBytes && existing != null && !expectedKey.equals(existing.getAvatarUrl())) {
                templateMapper.update(null, new LambdaUpdateWrapper<CharacterSquareTemplate>()
                        .eq(CharacterSquareTemplate::getId, existing.getId())
                        .set(CharacterSquareTemplate::getAvatarUrl, expectedKey));
                log.info("Square template avatar_url updated: slug={} -> {}", slug, expectedKey);
                return 0;
            }
        }
        String objectKey = fileStorageService.uploadSquareAvatar(slug, bytes, contentType);

        if (existing == null) {
            log.debug("No DB template for slug={}, avatar uploaded only", slug);
            return 1;
        }
        if (objectKey.equals(existing.getAvatarUrl())) {
            return 1;
        }
        templateMapper.update(null, new LambdaUpdateWrapper<CharacterSquareTemplate>()
                .eq(CharacterSquareTemplate::getId, existing.getId())
                .set(CharacterSquareTemplate::getAvatarUrl, objectKey));
        log.info("Square template avatar_url updated: slug={} -> {}", slug, objectKey);
        return 1;
    }

    private Map<String, Resource> loadSquareAvatarResources() {
        Map<String, Resource> map = new LinkedHashMap<>();
        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources("classpath:square-avatars/*.*");
            for (Resource resource : resources) {
                String filename = resource.getFilename();
                if (filename == null || !filename.contains(".")) {
                    continue;
                }
                String slug = filename.substring(0, filename.lastIndexOf('.'));
                map.put(slug, resource);
            }
        } catch (Exception e) {
            log.warn("Load square-avatars resources failed: {}", e.getMessage());
        }
        return map;
    }

    private String guessContentType(String filename) {
        String lower = filename.toLowerCase();
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        if (lower.endsWith(".webp")) {
            return "image/webp";
        }
        if (lower.endsWith(".gif")) {
            return "image/gif";
        }
        return "image/jpeg";
    }
}
