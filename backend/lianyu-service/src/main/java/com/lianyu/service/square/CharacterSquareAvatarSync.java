package com.lianyu.service.square;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.lianyu.dao.entity.Character;
import com.lianyu.dao.entity.CharacterSquareTemplate;
import com.lianyu.dao.mapper.CharacterMapper;
import com.lianyu.dao.mapper.CharacterSquareTemplateMapper;
import com.lianyu.service.storage.FileStorageService;
import java.io.InputStream;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

/**
 * 启动时将 classpath square-avatars/{slug}.* 同步到 MinIO，并写回模板 avatar_url（object key）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CharacterSquareAvatarSync implements ApplicationRunner {

    private final FileStorageService fileStorageService;
    private final CharacterSquareTemplateMapper templateMapper;
    private final CharacterMapper characterMapper;

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
        backfillMissingThumbs();
        backfillCharacterAvatarsFromTemplates();
    }

    private void backfillMissingThumbs() {
        List<CharacterSquareTemplate> templates = templateMapper.selectList(
                new LambdaQueryWrapper<CharacterSquareTemplate>()
                        .eq(CharacterSquareTemplate::getIsEnabled, 1)
                        .isNotNull(CharacterSquareTemplate::getAvatarUrl));
        if (templates.isEmpty()) {
            return;
        }
        int generated = 0;
        for (CharacterSquareTemplate template : templates) {
            if (fileStorageService.ensureSquareAvatarThumb(template.getAvatarUrl())) {
                generated++;
            }
        }
        if (generated > 0) {
            log.info("Square avatar thumb backfill generated={}", generated);
        }
    }

    /**
     * 广场模板头像更新后，同步已添加角色的 avatar_url（仅广场来源且未用自定义 avatars/ 上传）。
     */
    private void backfillCharacterAvatarsFromTemplates() {
        List<Character> rows = characterMapper.selectList(new LambdaQueryWrapper<Character>()
                .isNotNull(Character::getSourceTemplateId));
        if (rows.isEmpty()) {
            return;
        }
        int updated = 0;
        for (Character row : rows) {
            CharacterSquareTemplate template = templateMapper.selectById(row.getSourceTemplateId());
            if (template == null || template.getAvatarUrl() == null || template.getAvatarUrl().isBlank()) {
                continue;
            }
            String templateAvatar = template.getAvatarUrl().trim();
            String current = row.getAvatarUrl();
            if (current != null && current.startsWith("avatars/")) {
                continue;
            }
            if (current != null && current.equals(templateAvatar)) {
                continue;
            }
            if (current != null && !current.isBlank() && !current.startsWith("square-avatars/")) {
                continue;
            }
            characterMapper.update(null, new LambdaUpdateWrapper<Character>()
                    .eq(Character::getId, row.getId())
                    .set(Character::getAvatarUrl, templateAvatar));
            updated++;
        }
        if (updated > 0) {
            log.info("Character avatar backfill from square templates: updated={}", updated);
        }
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
                fileStorageService.ensureSquareAvatarThumb(expectedKey);
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
