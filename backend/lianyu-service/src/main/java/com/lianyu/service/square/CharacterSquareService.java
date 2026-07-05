package com.lianyu.service.square;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lianyu.common.base.ErrorCode;
import com.lianyu.common.exception.BusinessException;
import com.lianyu.common.i18n.OutputLanguage;
import com.lianyu.dao.entity.Character;
import com.lianyu.dao.entity.CharacterSquareTemplate;
import com.lianyu.dao.mapper.CharacterMapper;
import com.lianyu.dao.mapper.CharacterSquareTemplateMapper;
import com.lianyu.service.character.CharacterCitySettingsService;
import com.lianyu.service.character.CharacterService;
import com.lianyu.service.dto.CharacterResponse;
import com.lianyu.service.dto.CharacterSquarePageResponse;
import com.lianyu.service.dto.CharacterSquareTemplateCardResponse;
import com.lianyu.service.dto.CharacterSquareTemplateResponse;
import com.lianyu.service.storage.FileStorageService;
import com.lianyu.service.support.OutputLanguageService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class CharacterSquareService {

    public static final String HEADER_UI_LANGUAGE = "X-LianYu-Ui-Language";
    public static final int SQUARE_PAGE_SIZE_DEFAULT = 12;
    public static final int SQUARE_PAGE_SIZE_MAX = 100;

    private final CharacterSquareTemplateMapper templateMapper;
    private final CharacterMapper characterMapper;
    private final SquareLikeService squareLikeService;
    @Lazy
    private final CharacterService characterService;
    private final FileStorageService fileStorageService;
    private final OutputLanguageService outputLanguageService;
    private final CharacterCitySettingsService characterCitySettingsService;

    public CharacterSquarePageResponse listTemplatesPage(
            Long userId, String uiLanguageCode, String tag, String keyword, int page, int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(SQUARE_PAGE_SIZE_MAX, Math.max(1, size));
        String uiLang = OutputLanguage.fromCode(uiLanguageCode).getCode();
        List<CharacterSquareTemplateCardResponse> all = buildCardList(userId, uiLang);
        List<String> allTags = collectTagLabels(all);
        String tagFilter = tag != null ? tag.trim() : "";
        String keywordFilter = keyword != null ? keyword.trim() : "";
        List<CharacterSquareTemplateCardResponse> filtered = all;
        if (!tagFilter.isEmpty()) {
            filtered = filtered.stream()
                    .filter(item -> item.getTags() != null && item.getTags().contains(tagFilter))
                    .toList();
        }
        if (!keywordFilter.isEmpty()) {
            String lower = keywordFilter.toLowerCase();
            filtered = filtered.stream()
                    .filter(item -> item.getName() != null
                            && item.getName().toLowerCase().contains(lower))
                    .toList();
        }
        long total = filtered.size();
        int from = (safePage - 1) * safeSize;
        List<CharacterSquareTemplateCardResponse> records;
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
                .userLikes(all.isEmpty() ? Set.of() : squareLikeService.getUserLikes(userId))
                .build();
    }

    public CharacterSquareTemplateResponse getTemplateDetail(
            Long userId, Long templateId, String uiLanguageCode) {
        CharacterSquareTemplate template = templateMapper.selectById(templateId);
        requireEnabledKnownTemplate(template);
        String uiLang = OutputLanguage.fromCode(uiLanguageCode).getCode();
        Map<Long, Long> likeCounts = squareLikeService.getLikeCounts();
        Set<Long> userLikes = squareLikeService.getUserLikes(userId);
        Map<Long, Character> addedByTemplateId = loadAddedCharacters(userId);
        return stripPromptFromApi(toTemplateResponse(
                template,
                addedByTemplateId.get(template.getId()),
                uiLang,
                likeCounts.getOrDefault(template.getId(), 0L),
                userLikes.contains(template.getId())));
    }

    private CharacterSquareTemplateResponse stripPromptFromApi(CharacterSquareTemplateResponse response) {
        if (response == null) {
            return null;
        }
        response.setPromptTemplate(null);
        return response;
    }

    private List<CharacterSquareTemplateCardResponse> buildCardList(Long userId, String uiLang) {
        List<CharacterSquareTemplate> templates = templateMapper.selectList(
                new LambdaQueryWrapper<CharacterSquareTemplate>()
                        .eq(CharacterSquareTemplate::getIsEnabled, 1)
                        .isNotNull(CharacterSquareTemplate::getSlug));
        if (templates.isEmpty()) {
            return List.of();
        }

        Map<Long, Long> likeCounts = squareLikeService.getLikeCounts();
        Set<Long> userLikes = squareLikeService.getUserLikes(userId);
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
                .sorted(likeCountComparator(likeCounts))
                .map(t -> toCardResponse(
                        t,
                        addedByTemplateId.get(t.getId()),
                        uiLang,
                        likeCounts.getOrDefault(t.getId(), 0L),
                        userLikes.contains(t.getId())))
                .toList();
    }

    private java.util.Comparator<CharacterSquareTemplate> likeCountComparator(Map<Long, Long> likeCounts) {
        return (a, b) -> {
            long likeA = likeCounts.getOrDefault(a.getId(), 0L);
            long likeB = likeCounts.getOrDefault(b.getId(), 0L);
            if (likeA != likeB) {
                return Long.compare(likeB, likeA);
            }
            int sortA = a.getSortOrder() != null ? a.getSortOrder() : 0;
            int sortB = b.getSortOrder() != null ? b.getSortOrder() : 0;
            if (sortA != sortB) {
                return Integer.compare(sortA, sortB);
            }
            return Long.compare(a.getId(), b.getId());
        };
    }

    private List<String> collectTagLabels(List<CharacterSquareTemplateCardResponse> templates) {
        LinkedHashSet<String> tags = new LinkedHashSet<>();
        for (CharacterSquareTemplateCardResponse item : templates) {
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
    public CharacterResponse addTemplateToMyCharacters(Long userId,
                                                       Long templateId,
                                                       String uiLanguageCode,
                                                       String cityMode,
                                                       String userCity) {
        CharacterSquareTemplate template = templateMapper.selectById(templateId);
        String slug = requireEnabledKnownTemplate(template);

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
        String resolvedName = uiPack != null ? uiPack.name() : template.getName();
        String resolvedPrompt = promptPack != null ? promptPack.prompt() : template.getPromptTemplate();
        if (resolvedName == null || resolvedName.isBlank()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "角色模板不存在或已下架");
        }

        Character entity = new Character();
        entity.setOwnerUserId(userId);
        entity.setSourceTemplateId(templateId);
        entity.setName(resolvedName);
        entity.setAvatarUrl(template.getAvatarUrl());
        entity.setPromptTemplate(resolvedPrompt);
        Map<String, Object> settings = copySettings(template.getSettingsJson());
        characterCitySettingsService.applySquareAddCity(
                userId, resolvedName, resolvedPrompt, settings, cityMode, userCity);
        entity.setSettings(settings);

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

    private CharacterSquareTemplateCardResponse toCardResponse(
            CharacterSquareTemplate template,
            Character added,
            String uiLang,
            long likeCount,
            boolean liked) {
        String slug = normalizeSlug(template);
        CharacterSquareCatalog.LocalePack pack = CharacterSquareCatalog.resolve(slug, uiLang);
        String avatarUrl = fileStorageService.resolvePublicUrl(template.getAvatarUrl());
        String thumbUrl = fileStorageService.resolveSquareAvatarThumbPublicUrl(template.getAvatarUrl());
        if (pack == null) {
            return CharacterSquareTemplateCardResponse.builder()
                    .id(template.getId())
                    .slug(slug)
                    .name(template.getName())
                    .summary(template.getSummary())
                    .avatarThumbUrl(thumbUrl)
                    .avatarUrl(avatarUrl)
                    .tags(filterKnownLabels(parseTagKeys(template.getTagsJson()), template.getSettingsJson()))
                    .added(added != null)
                    .addedCharacterId(added != null ? added.getId() : null)
                    .likeCount(likeCount)
                    .liked(liked)
                    .build();
        }

        List<String> tagLabels = pack.tags().stream().map(CharacterSquareCatalog.Tag::label).toList();

        return CharacterSquareTemplateCardResponse.builder()
                .id(template.getId())
                .slug(slug)
                .name(pack.name())
                .summary(pack.summary())
                .avatarThumbUrl(thumbUrl)
                .avatarUrl(avatarUrl)
                .tags(tagLabels)
                .added(added != null)
                .addedCharacterId(added != null ? added.getId() : null)
                .likeCount(likeCount)
                .liked(liked)
                .build();
    }

    private CharacterSquareTemplateResponse toTemplateResponse(
            CharacterSquareTemplate template,
            Character added,
            String uiLang,
            long likeCount,
            boolean liked) {
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
                    .tags(parseTagKeys(template.getTagsJson()))
                    .tagKeys(parseTagKeys(template.getTagsJson()))
                    .added(added != null)
                    .addedCharacterId(added != null ? added.getId() : null)
                    .likeCount(likeCount)
                    .liked(liked)
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
                .likeCount(likeCount)
                .liked(liked)
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

    private static boolean isTemplateEnabled(CharacterSquareTemplate template) {
        return template != null && template.getIsEnabled() != null && template.getIsEnabled() == 1;
    }

    private String requireEnabledKnownTemplate(CharacterSquareTemplate template) {
        if (!isTemplateEnabled(template)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "角色模板不存在或已下架");
        }
        String slug = normalizeSlug(template);
        if (slug == null || !CharacterSquareCatalog.isKnownSlug(slug)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "角色模板不存在或已下架");
        }
        return slug;
    }

    private Map<String, Object> copySettings(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return null;
        }
        return new LinkedHashMap<>(source);
    }

    private List<String> filterKnownLabels(List<String> labels, java.util.Map<String, Object> settings) {
        if (labels == null) {
            return List.of();
        }
        boolean allowNew = settings != null && Boolean.TRUE.equals(settings.get("allowNewTags"));
        if (allowNew) {
            return List.copyOf(labels);
        }
        return labels.stream()
                .filter(CharacterSquareTags::isKnownLabel)
                .toList();
    }
}
