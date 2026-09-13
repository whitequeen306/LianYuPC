package com.lianyu.service.character;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lianyu.common.base.Result;
import com.lianyu.dao.entity.Character;
import com.lianyu.dao.entity.CharacterDiary;
import com.lianyu.dao.entity.CharacterState;
import com.lianyu.dao.mapper.CharacterMapper;
import com.lianyu.service.relationship.RelationshipInnerSpace;
import com.lianyu.service.relationship.RelationshipStateService;
import com.lianyu.service.storage.FileStorageService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 角色情绪状态 / 日记的查询组装（从 CharacterStateController 下沉，
 * docs/refactor-plan-coupling.md 项11）：Mapper 查询与视图组装属业务逻辑，Controller 只留协议壳。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CharacterStateQueryService {

    private final CharacterStateService characterStateService;
    private final CharacterDiaryService diaryService;
    private final CharacterMapper characterMapper;
    private final FileStorageService fileStorageService;
    private final RelationshipStateService relationshipStateService;

    public Result<Map<String, Object>> getState(long userId, Long characterId) {
        CharacterState state = characterStateService.getOrCreate(characterId, userId);
        Character character = characterMapper.selectById(characterId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("characterId", state.getCharacterId());
        result.put("characterName", character != null ? character.getName() : "角色#" + characterId);
        result.put("currentEmotion", state.getCurrentEmotion());
        result.put("emotionIntensity", state.getEmotionIntensity());
        result.put("statusText", state.getStatusText());
        result.put("emotionUpdatedAt", state.getEmotionUpdatedAt());
        putInnerSpace(result, userId, characterId);
        return Result.ok(result);
    }

    public Result<List<Map<String, Object>>> listStates(long userId) {
        List<Character> characters = characterMapper.selectList(
                new LambdaQueryWrapper<Character>()
                        .eq(Character::getOwnerUserId, userId));

        List<Long> characterIds = characters.stream().map(Character::getId).toList();
        Map<Long, CharacterState> stateMap = characterStateService.mapForCharacters(userId, characterIds);

        List<Map<String, Object>> result = new ArrayList<>();
        for (Character character : characters) {
            CharacterState state = stateMap.get(character.getId());
            if (state == null) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("characterId", state.getCharacterId());
            item.put("characterName", character.getName());
            item.put("avatarUrl", fileStorageService.resolvePublicUrl(character.getAvatarUrl()));
            item.put("avatarThumbUrl", fileStorageService.resolveSquareAvatarThumbPublicUrl(character.getAvatarUrl()));
            item.put("currentEmotion", state.getCurrentEmotion());
            item.put("emotionIntensity", state.getEmotionIntensity());
            item.put("statusText", state.getStatusText());
            putInnerSpace(item, userId, character.getId());
            result.add(item);
        }
        return Result.ok(result);
    }

    public Result<List<Map<String, Object>>> listDiaries(long userId, Long characterId, int page, int size) {
        List<CharacterDiary> diaries = diaryService.listDiaries(userId, characterId, page, size);
        Character character = characterMapper.selectById(characterId);

        List<Map<String, Object>> result = new ArrayList<>();
        for (CharacterDiary diary : diaries) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", diary.getId());
            item.put("characterId", diary.getCharacterId());
            item.put("characterName", character != null ? character.getName() : "角色#" + characterId);
            item.put("avatarUrl", character != null
                    ? fileStorageService.resolvePublicUrl(character.getAvatarUrl())
                    : null);
            item.put("avatarThumbUrl", character != null
                    ? fileStorageService.resolveSquareAvatarThumbPublicUrl(character.getAvatarUrl())
                    : null);
            item.put("title", diary.getTitle());
            item.put("content", diary.getContent());
            item.put("mood", diary.getMood());
            item.put("createdAt", diary.getCreatedAt());
            result.add(item);
        }
        return Result.ok(result);
    }

    public Result<Map<String, Object>> getDiary(long userId, Long diaryId) {
        CharacterDiary diary = diaryService.getDiary(userId, diaryId);
        if (diary == null) {
            return Result.fail(404, "日记不存在");
        }
        Character character = characterMapper.selectById(diary.getCharacterId());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", diary.getId());
        result.put("characterId", diary.getCharacterId());
        result.put("characterName", character != null ? character.getName() : "角色#" + diary.getCharacterId());
        result.put("title", diary.getTitle());
        result.put("content", diary.getContent());
        result.put("mood", diary.getMood());
        result.put("createdAt", diary.getCreatedAt());
        return Result.ok(result);
    }

    public Result<List<Map<String, Object>>> listAllDiaries(long userId, int page, int size) {
        List<CharacterDiary> diaries = diaryService.listDiaries(userId, null, page, size);

        Set<Long> characterIds = diaries.stream()
                .map(CharacterDiary::getCharacterId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, Character> characterMap = new HashMap<>();
        if (!characterIds.isEmpty()) {
            characterMapper.selectBatchIds(characterIds).forEach(c -> {
                if (c != null) {
                    characterMap.put(c.getId(), c);
                }
            });
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (CharacterDiary diary : diaries) {
            Character character = characterMap.get(diary.getCharacterId());
            if (character == null || !character.getOwnerUserId().equals(userId)) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", diary.getId());
            item.put("characterId", diary.getCharacterId());
            item.put("characterName", character.getName());
            item.put("avatarUrl", fileStorageService.resolvePublicUrl(character.getAvatarUrl()));
            item.put("avatarThumbUrl",
                    fileStorageService.resolveSquareAvatarThumbPublicUrl(character.getAvatarUrl()));
            item.put("title", diary.getTitle());
            item.put("content", diary.getContent());
            item.put("mood", diary.getMood());
            item.put("createdAt", diary.getCreatedAt());
            result.add(item);
        }
        return Result.ok(result);
    }

    private void putInnerSpace(Map<String, Object> item, Long userId, Long characterId) {
        RelationshipInnerSpace innerSpace = safeInnerSpace(userId, characterId);
        item.put("innerSpaceHeadline", innerSpace.headline());
        item.put("innerSpaceBody", innerSpace.body());
    }

    private RelationshipInnerSpace safeInnerSpace(Long userId, Long characterId) {
        try {
            return relationshipStateService.buildInnerSpace(userId, characterId);
        } catch (RuntimeException e) {
            log.warn("Inner space build failed: userId={}, characterId={}, reason={}",
                    userId, characterId, e.getMessage());
            return RelationshipInnerSpace.defaultSpace();
        }
    }
}
