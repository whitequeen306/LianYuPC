package com.lianyu.service.character;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lianyu.common.base.ErrorCode;
import com.lianyu.common.exception.BusinessException;
import com.lianyu.common.util.CharacterSettingsUtils;
import com.lianyu.dao.entity.Character;
import com.lianyu.dao.entity.CharacterDiary;
import com.lianyu.dao.entity.CharacterState;
import com.lianyu.dao.entity.Conversation;
import com.lianyu.dao.entity.GroupMember;
import com.lianyu.dao.entity.MemoryMeta;
import com.lianyu.dao.entity.Message;
import com.lianyu.dao.entity.MomentsComment;
import com.lianyu.dao.entity.MomentsInteractionState;
import com.lianyu.dao.entity.MomentsPost;
import com.lianyu.dao.mapper.CharacterDiaryMapper;
import com.lianyu.dao.mapper.CharacterMapper;
import com.lianyu.dao.mapper.CharacterStateMapper;
import com.lianyu.dao.mapper.ConversationMapper;
import com.lianyu.dao.mapper.GroupMemberMapper;
import com.lianyu.dao.mapper.MemoryMetaMapper;
import com.lianyu.dao.mapper.MessageMapper;
import com.lianyu.dao.mapper.MomentsCommentMapper;
import com.lianyu.dao.mapper.MomentsInteractionStateMapper;
import com.lianyu.dao.mapper.MomentsPostMapper;
import com.lianyu.service.dto.CharacterResponse;
import com.lianyu.service.dto.CreateCharacterRequest;
import com.lianyu.service.dto.UpdateCharacterRequest;
import com.lianyu.service.memory.MemoryCacheService;
import com.lianyu.service.memory.MemoryWriter;
import com.lianyu.service.storage.FileStorageService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class CharacterService {

    private static final String MESSAGE_SEQ_KEY_PREFIX = "msg_seq:";
    private static final String GROUP_TURN_KEY_PREFIX = "group_chat:turn:";

    private final CharacterMapper characterMapper;
    private final ConversationMapper conversationMapper;
    private final MessageMapper messageMapper;
    private final MemoryMetaMapper memoryMetaMapper;
    private final GroupMemberMapper groupMemberMapper;
    private final MomentsPostMapper momentsPostMapper;
    private final MomentsCommentMapper momentsCommentMapper;
    private final MomentsInteractionStateMapper momentsInteractionStateMapper;
    private final CharacterStateMapper characterStateMapper;
    private final CharacterDiaryMapper characterDiaryMapper;
    private final MemoryWriter memoryWriter;
    private final MemoryCacheService memoryCacheService;
    private final StringRedisTemplate redisTemplate;
    private final FileStorageService fileStorageService;

    @Value("${lianyu.character.max-per-user:80}")
    private int maxCharactersPerUser;

    @Transactional
    public CharacterResponse create(Long userId, CreateCharacterRequest request) {
        assertCharacterLimit(userId);
        Character entity = new Character();
        entity.setOwnerUserId(userId);
        entity.setName(request.getName());
        entity.setAvatarUrl(request.getAvatarUrl());
        entity.setSettings(CharacterSettingsUtils.normalizeSettings(request.getSettings()));
        entity.setPromptTemplate(request.getPromptTemplate());
        characterMapper.insert(entity);

        log.info("Character created: id={}, name={}, userId={}", entity.getId(), entity.getName(), userId);
        return toResponse(entity);
    }

    public List<CharacterResponse> list(Long userId) {
        List<Character> entities = characterMapper.selectList(new LambdaQueryWrapper<Character>()
                .eq(Character::getOwnerUserId, userId)
                .orderByDesc(Character::getUpdatedAt));
        return entities.stream().map(this::toResponse).toList();
    }

    public CharacterResponse get(Long userId, Long characterId) {
        Character entity = findOwned(userId, characterId);
        return toResponse(entity);
    }

    @Transactional
    public CharacterResponse update(Long userId, Long characterId, UpdateCharacterRequest request) {
        Character entity = findOwned(userId, characterId);

        if (request.getName() != null) {
            entity.setName(request.getName());
        }
        if (request.getAvatarUrl() != null) {
            entity.setAvatarUrl(request.getAvatarUrl());
        }
        if (request.getSettings() != null) {
            entity.setSettings(CharacterSettingsUtils.normalizeSettings(
                    mergeSettings(entity.getSettings(), request.getSettings())));
        }
        if (request.getPromptTemplate() != null) {
            entity.setPromptTemplate(request.getPromptTemplate());
        }
        characterMapper.updateById(entity);

        log.info("Character updated: id={}, name={}", characterId, entity.getName());
        return toResponse(entity);
    }

    @Transactional
    public void delete(Long userId, Long characterId) {
        Character entity = findOwned(userId, characterId);

        List<Conversation> directConversations = conversationMapper.selectList(new LambdaQueryWrapper<Conversation>()
                .eq(Conversation::getUserId, userId)
                .eq(Conversation::getCharacterId, characterId));
        deleteConversations(directConversations);

        List<Conversation> groupConversations = conversationMapper.selectList(new LambdaQueryWrapper<Conversation>()
                .eq(Conversation::getUserId, userId)
                .eq(Conversation::getMode, "GROUP")
                .inSql(Conversation::getId,
                        "select conversation_id from group_member where character_id = " + characterId));
        deleteConversations(groupConversations);

        List<MemoryMeta> memories = memoryMetaMapper.selectList(new LambdaQueryWrapper<MemoryMeta>()
                .eq(MemoryMeta::getUserId, userId)
                .eq(MemoryMeta::getCharacterId, characterId));
        memoryWriter.deleteVectors(memories.stream()
                .map(MemoryMeta::getMilvusVecId)
                .filter(id -> id != null && !id.isBlank())
                .toList());
        memoryMetaMapper.delete(new LambdaQueryWrapper<MemoryMeta>()
                .eq(MemoryMeta::getUserId, userId)
                .eq(MemoryMeta::getCharacterId, characterId));
        memoryCacheService.invalidate(userId, characterId);

        deleteMomentsForCharacter(userId, characterId);
        characterDiaryMapper.delete(new LambdaQueryWrapper<CharacterDiary>()
                .eq(CharacterDiary::getUserId, userId)
                .eq(CharacterDiary::getCharacterId, characterId));
        characterStateMapper.delete(new LambdaQueryWrapper<CharacterState>()
                .eq(CharacterState::getUserId, userId)
                .eq(CharacterState::getCharacterId, characterId));

        groupMemberMapper.delete(new LambdaQueryWrapper<GroupMember>()
                .eq(GroupMember::getCharacterId, characterId));
        messageMapper.delete(new LambdaQueryWrapper<Message>()
                .eq(Message::getCharacterId, characterId));
        characterMapper.deleteById(characterId);

        log.info("Character deleted with related data: id={}, name={}", characterId, entity.getName());
    }

    private void deleteMomentsForCharacter(Long userId, Long characterId) {
        List<MomentsPost> posts = momentsPostMapper.selectList(new LambdaQueryWrapper<MomentsPost>()
                .eq(MomentsPost::getUserId, userId)
                .eq(MomentsPost::getCharacterId, characterId));
        if (posts.isEmpty()) {
            return;
        }
        List<Long> postIds = posts.stream()
                .map(MomentsPost::getId)
                .filter(Objects::nonNull)
                .toList();
        if (!postIds.isEmpty()) {
            momentsCommentMapper.delete(new LambdaQueryWrapper<MomentsComment>()
                    .in(MomentsComment::getPostId, postIds));
            momentsInteractionStateMapper.delete(new LambdaQueryWrapper<MomentsInteractionState>()
                    .in(MomentsInteractionState::getPostId, postIds));
        }
        momentsPostMapper.delete(new LambdaQueryWrapper<MomentsPost>()
                .eq(MomentsPost::getUserId, userId)
                .eq(MomentsPost::getCharacterId, characterId));
    }

    private void deleteConversations(List<Conversation> conversations) {
        if (conversations == null || conversations.isEmpty()) {
            return;
        }
        List<Long> conversationIds = conversations.stream()
                .map(Conversation::getId)
                .distinct()
                .toList();
        for (Long conversationId : conversationIds) {
            messageMapper.delete(new LambdaQueryWrapper<Message>()
                    .eq(Message::getConversationId, conversationId));
            groupMemberMapper.delete(new LambdaQueryWrapper<GroupMember>()
                    .eq(GroupMember::getConversationId, conversationId));
            conversationMapper.deleteById(conversationId);
            redisTemplate.delete(List.of(
                    MESSAGE_SEQ_KEY_PREFIX + conversationId,
                    GROUP_TURN_KEY_PREFIX + conversationId
            ));
        }
    }

    private Map<String, Object> mergeSettings(Map<String, Object> existing, Map<String, Object> updates) {
        Map<String, Object> merged = new LinkedHashMap<>();
        if (existing != null) {
            merged.putAll(existing);
        }
        if (updates != null) {
            updates.forEach((key, value) -> {
                if (value == null) {
                    merged.remove(key);
                } else {
                    merged.put(key, value);
                }
            });
        }
        return merged.isEmpty() ? null : merged;
    }

    public void assertCharacterLimit(Long userId) {
        long count = characterMapper.selectCount(new LambdaQueryWrapper<Character>()
                .eq(Character::getOwnerUserId, userId));
        if (count >= maxCharactersPerUser) {
            throw new BusinessException(ErrorCode.CHARACTER_LIMIT,
                    "角色数量已达上限（" + maxCharactersPerUser + "）");
        }
    }

    private Character findOwned(Long userId, Long characterId) {
        Character entity = characterMapper.selectById(characterId);
        if (entity == null || !entity.getOwnerUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.CHARACTER_NOT_FOUND);
        }
        return entity;
    }

    private CharacterResponse toResponse(Character entity) {
        return CharacterResponse.builder()
                .id(entity.getId())
                .ownerUserId(entity.getOwnerUserId())
                .name(entity.getName())
                .avatarUrl(fileStorageService.resolvePublicUrl(entity.getAvatarUrl()))
                .settings(CharacterSettingsUtils.normalizeSettings(entity.getSettings()))
                .promptTemplate(entity.getPromptTemplate())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }
}
