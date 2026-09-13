package com.lianyu.service.memory;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lianyu.common.base.Result;
import com.lianyu.dao.entity.Character;
import com.lianyu.dao.entity.Conversation;
import com.lianyu.dao.entity.MemoryMeta;
import com.lianyu.dao.entity.Message;
import com.lianyu.dao.mapper.CharacterMapper;
import com.lianyu.dao.mapper.ConversationMapper;
import com.lianyu.dao.mapper.MemoryMetaMapper;
import com.lianyu.dao.mapper.MessageMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 用户侧记忆查询/删除（从 MemoryController 下沉，docs/refactor-plan-coupling.md 项11）：
 * 查询组装、越权过滤与 Milvus 向量清理属于业务逻辑，Controller 只留协议壳。
 */
@Service
@RequiredArgsConstructor
public class MemoryQueryService {

    private static final int MAX_LIST_SIZE = 200;

    private final MemoryMetaMapper memoryMetaMapper;
    private final MessageMapper messageMapper;
    private final CharacterMapper characterMapper;
    private final ConversationMapper conversationMapper;
    private final MemoryWriter memoryWriter;
    private final MemoryCacheService memoryCacheService;

    public Result<List<Map<String, Object>>> list(
            long userId, Long characterId, int page, int size) {
        int safeSize = Math.min(MAX_LIST_SIZE, Math.max(1, size));
        int safePage = Math.max(1, page);
        int offset = (safePage - 1) * safeSize;

        LambdaQueryWrapper<MemoryMeta> q = new LambdaQueryWrapper<MemoryMeta>()
                .eq(MemoryMeta::getUserId, userId)
                .orderByDesc(MemoryMeta::getCreatedAt);
        if (characterId != null) {
            q.eq(MemoryMeta::getCharacterId, characterId);
        }
        q.last("LIMIT " + safeSize + " OFFSET " + offset);

        List<MemoryMeta> metas = memoryMetaMapper.selectList(q);
        Set<Long> characterIds = metas.stream()
                .map(MemoryMeta::getCharacterId)
                .filter(id -> id != null && id > 0)
                .collect(Collectors.toSet());
        Map<Long, Character> characterMap = characterIds.isEmpty()
                ? Map.of()
                : characterMapper.selectBatchIds(characterIds).stream()
                        .collect(Collectors.toMap(Character::getId, c -> c, (a, b) -> a));

        List<Map<String, Object>> result = metas.stream().map(m -> {
            Character character = characterMap.get(m.getCharacterId());
            Map<String, Object> m1 = new LinkedHashMap<>();
            m1.put("id", m.getId());
            m1.put("characterId", m.getCharacterId());
            m1.put("characterName", character != null ? character.getName() : "角色#" + m.getCharacterId());
            m1.put("summary", m.getSummary());
            m1.put("importance", m.getImportance());
            m1.put("sourceMsgIds", m.getSourceMsgIds());
            m1.put("createdAt", m.getCreatedAt());
            return m1;
        }).toList();

        return Result.ok(result);
    }

    public Result<Map<String, Object>> detail(long userId, Long id) {
        MemoryMeta meta = memoryMetaMapper.selectOne(
                new LambdaQueryWrapper<MemoryMeta>()
                        .eq(MemoryMeta::getId, id)
                        .eq(MemoryMeta::getUserId, userId));
        if (meta == null) {
            return Result.fail(404, "记忆不存在");
        }

        List<Message> sourceMsgs = List.of();
        if (meta.getSourceMsgIds() != null && !meta.getSourceMsgIds().isEmpty()) {
            sourceMsgs = messageMapper.selectBatchIds(meta.getSourceMsgIds());
            sourceMsgs = filterMessagesOwnedByUser(userId, sourceMsgs);
        }
        Character character = characterMapper.selectById(meta.getCharacterId());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", meta.getId());
        result.put("characterId", meta.getCharacterId());
        result.put("characterName", character != null ? character.getName() : "角色#" + meta.getCharacterId());
        result.put("summary", meta.getSummary());
        result.put("sourceMsgIds", meta.getSourceMsgIds());
        result.put("createdAt", meta.getCreatedAt());
        result.put("sourceMessages", sourceMsgs.stream().map(msg -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", msg.getId());
            m.put("role", msg.getRole());
            m.put("content", msg.getContent());
            m.put("createdAt", msg.getCreatedAt());
            return m;
        }).toList());

        return Result.ok(result);
    }

    public Result<Void> delete(long userId, Long id) {
        MemoryMeta meta = memoryMetaMapper.selectOne(
                new LambdaQueryWrapper<MemoryMeta>()
                        .eq(MemoryMeta::getId, id)
                        .eq(MemoryMeta::getUserId, userId));
        if (meta == null) {
            return Result.fail(404, "记忆不存在");
        }

        memoryWriter.deleteVectors(meta.getMilvusVecId() == null ? List.of() : List.of(meta.getMilvusVecId()));
        memoryMetaMapper.deleteById(id);
        memoryCacheService.invalidate(userId, meta.getCharacterId());
        return Result.ok();
    }

    private List<Message> filterMessagesOwnedByUser(long userId, List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        Set<Long> conversationIds = messages.stream()
                .map(Message::getConversationId)
                .filter(id -> id != null)
                .collect(Collectors.toSet());
        if (conversationIds.isEmpty()) {
            return List.of();
        }
        List<Conversation> owned = conversationMapper.selectList(new LambdaQueryWrapper<Conversation>()
                .in(Conversation::getId, conversationIds)
                .eq(Conversation::getUserId, userId));
        Set<Long> ownedIds = owned.stream().map(Conversation::getId).collect(Collectors.toSet());
        return messages.stream()
                .filter(m -> m.getConversationId() != null && ownedIds.contains(m.getConversationId()))
                .toList();
    }
}
