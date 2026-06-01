package com.lianyu.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lianyu.common.base.ErrorCode;
import com.lianyu.common.exception.BusinessException;
import com.lianyu.common.i18n.OutputLanguage;
import com.lianyu.dao.entity.Character;
import com.lianyu.dao.entity.CharacterSquareTemplate;
import com.lianyu.dao.mapper.CharacterMapper;
import com.lianyu.dao.mapper.CharacterSquareTemplateMapper;
import com.lianyu.service.dto.CharacterResponse;
import com.lianyu.service.dto.CharacterSquarePageResponse;
import com.lianyu.service.dto.CharacterSquareTemplateResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class CharacterSquareService {

    public static final String HEADER_UI_LANGUAGE = "X-LianYu-Ui-Language";
    public static final int SQUARE_PAGE_SIZE_DEFAULT = 12;

    private final CharacterSquareTemplateMapper templateMapper;
    private final CharacterMapper characterMapper;
    @Lazy
    private final CharacterService characterService;
    private final FileStorageService fileStorageService;
    private final OutputLanguageService outputLanguageService;

    public CharacterSquarePageResponse listTemplatesPage(
            Long userId, String uiLanguageCode, String tag, int page, int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(48, Math.max(1, size));
        String uiLang = OutputLanguage.fromCode(uiLanguageCode).getCode();
        List<CharacterSquareTemplateResponse> all = buildTemplateList(userId, uiLang);
        List<String> allTags = collectTagLabels(all);
        String tagFilter = tag != null ? tag.trim() : "";
        List<CharacterSquareTemplateResponse> filtered = all;
        if (!tagFilter.isEmpty()) {
            filtered = all.stream()
                    .filter(item -> item.getTags() != null && item.getTags().contains(tagFilter))
                    .toList();
        }
        long total = filtered.size();
        int from = (safePage - 1) * safeSize;
        List<CharacterSquareTemplateResponse> records;
        if (from >= filtered.size()) {
            records = List.of();
        } else {
            int to = Math.min(from + safeSize, filtered.size());
            records = filtered.subList(from, to);
        }
        return CharacterSquarePageResponse.builder()
                .records(records)
                .total(total)
                .page(safePage)
                .size(safeSize)
                .tags(allTags)
                .build();
    }

    private List<CharacterSquareTemplateResponse> buildTemplateList(Long userId, String uiLang) {
        List<CharacterSquareTemplate> templates = templateMapper.selectList(
                new LambdaQueryWrapper<CharacterSquareTemplate>()
                        .eq(CharacterSquareTemplate::getIsEnabled, 1)
                        .isNotNull(CharacterSquareTemplate::getSlug)
                        .orderByAsc(CharacterSquareTemplate::getSortOrder)
                        .orderByAsc(CharacterSquareTemplate::getId));
        if (templates.isEmpty()) {
            return List.of();
        }

        Map<Long, Character> addedByTemplateId = loadAddedCharacters(userId);
        Map<String, CharacterSquareTemplate> bySlug = new LinkedHashMap<>();
        for (CharacterSquareTemplate template : templates) {
            String slug = normalizeSlug(template);
            if (slug == null || !CharacterSquareCatalog.isKnownSlug(slug)) {
                continue;
            }
            bySlug.putIfAbsent(slug, template);
        }

        return bySlug.values().stream()
                .map(t -> toTemplateResponse(t, addedByTemplateId.get(t.getId()), uiLang))
                .toList();
    }

    private List<String> collectTagLabels(List<CharacterSquareTemplateResponse> templates) {
        LinkedHashSet<String> tags = new LinkedHashSet<>();
        for (CharacterSquareTemplateResponse item : templates) {
            if (item.getTags() == null) {
                continue;
            }
            for (String label : item.getTags()) {
                if (label != null && !label.isBlank()) {
                    tags.add(label);
                }
            }
        }
        return List.copyOf(tags);
    }

    @Transactional
    public CharacterResponse addTemplateToMyCharacters(Long userId, Long templateId, String uiLanguageCode) {
        CharacterSquareTemplate template = templateMapper.selectById(templateId);
        if (template == null || template.getIsEnabled() == null || template.getIsEnabled() != 1) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "角色模板不存在或已下架");
        }

        String slug = normalizeSlug(template);
        if (slug == null || !CharacterSquareCatalog.isKnownSlug(slug)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "角色模板不存在或已下架");
        }

        Character existing = characterMapper.selectOne(new LambdaQueryWrapper<Character>()
                .eq(Character::getOwnerUserId, userId)
                .eq(Character::getSourceTemplateId, templateId)
                .last("LIMIT 1"));
        if (existing != null) {
            throw new BusinessException(ErrorCode.CHARACTER_ALREADY_ADDED);
        }

        characterService.assertCharacterLimit(userId);

        String modelLang = outputLanguageService.resolveForUser(userId);
        String uiLang = OutputLanguage.fromCode(uiLanguageCode).getCode();
        CharacterSquareCatalog.LocalePack uiPack = CharacterSquareCatalog.resolve(slug, uiLang);
        CharacterSquareCatalog.LocalePack promptPack = CharacterSquareCatalog.resolve(slug, modelLang);
        if (uiPack == null || promptPack == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "角色模板不存在或已下架");
        }

        Character entity = new Character();
        entity.setOwnerUserId(userId);
        entity.setSourceTemplateId(templateId);
        entity.setName(uiPack.name());
        entity.setAvatarUrl(template.getAvatarUrl());
        entity.setPromptTemplate(promptPack.prompt());
        entity.setSettings(copySettings(template.getSettingsJson()));

        try {
            characterMapper.insert(entity);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(ErrorCode.CHARACTER_ALREADY_ADDED);
        }

        log.info("Character added from square: userId={}, templateId={}, slug={}, characterId={}",
                userId, templateId, slug, entity.getId());
        return characterService.get(userId, entity.getId());
    }

    private Map<Long, Character> loadAddedCharacters(Long userId) {
        List<Character> added = characterMapper.selectList(new LambdaQueryWrapper<Character>()
                .eq(Character::getOwnerUserId, userId)
                .isNotNull(Character::getSourceTemplateId));
        if (added.isEmpty()) {
            return Map.of();
        }
        return added.stream()
                .filter(c -> c.getSourceTemplateId() != null)
                .collect(Collectors.toMap(Character::getSourceTemplateId, c -> c, (a, b) -> a));
    }

    private CharacterSquareTemplateResponse toTemplateResponse(
            CharacterSquareTemplate template, Character added, String uiLang) {
        String slug = normalizeSlug(template);
        CharacterSquareCatalog.LocalePack pack = CharacterSquareCatalog.resolve(slug, uiLang);
        if (pack == null) {
            return CharacterSquareTemplateResponse.builder()
                    .id(template.getId())
                    .slug(slug)
                    .name(template.getName())
                    .summary(template.getSummary())
                    .avatarUrl(fileStorageService.resolvePublicUrl(template.getAvatarUrl()))
                    .promptTemplate(template.getPromptTemplate())
                    .tags(parseTagLabels(template.getTagsJson()))
                    .tagKeys(parseTagKeys(template.getTagsJson()))
                    .added(added != null)
                    .addedCharacterId(added != null ? added.getId() : null)
                    .build();
        }

        List<String> tagKeys = pack.tags().stream().map(CharacterSquareCatalog.Tag::key).toList();
        List<String> tagLabels = pack.tags().stream().map(CharacterSquareCatalog.Tag::label).toList();

        return CharacterSquareTemplateResponse.builder()
                .id(template.getId())
                .slug(slug)
                .name(pack.name())
                .summary(pack.summary())
                .avatarUrl(fileStorageService.resolvePublicUrl(template.getAvatarUrl()))
                .promptTemplate(pack.prompt())
                .tags(tagLabels)
                .tagKeys(tagKeys)
                .added(added != null)
                .addedCharacterId(added != null ? added.getId() : null)
                .build();
    }

    private String normalizeSlug(CharacterSquareTemplate template) {
        if (template.getSlug() != null && !template.getSlug().isBlank()) {
            return template.getSlug().trim();
        }
        return CharacterSquareCatalog.slugForSortOrder(
                template.getSortOrder() != null ? template.getSortOrder() : 0);
    }

    private List<String> parseTagKeys(Object raw) {
        List<String> keys = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item != null) {
                    String s = String.valueOf(item).trim();
                    if (!s.isBlank()) {
                        keys.add(s);
                    }
                }
            }
        }
        return keys;
    }

    private List<String> parseTagLabels(Object raw) {
        return parseTagKeys(raw);
    }

    private Map<String, Object> copySettings(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return null;
        }
        return new LinkedHashMap<>(source);
    }
}
