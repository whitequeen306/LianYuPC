package com.lianyu.web.controller;

import cn.dev33.satoken.stp.StpUtil;
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
import com.lianyu.service.MemoryWriter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Tag(name = "Memory", description = "记忆管理")
@RestController
@RequestMapping("/api/memory")
@RequiredArgsConstructor
public class MemoryController {

    private static final int MAX_LIST_SIZE = 200;

    private final MemoryMetaMapper memoryMetaMapper;
    private final MessageMapper messageMapper;
    private final CharacterMapper characterMapper;
    private final ConversationMapper conversationMapper;
    private final MemoryWriter memoryWriter;

    @Operation(summary = "记忆列表（按角色分组）")
    @GetMapping
    public Result<List<Map<String, Object>>> list(
            @RequestParam(required = false) Long characterId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int size) {
        long userId = StpUtil.getLoginIdAsLong();
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
        var result = metas.stream().map(m -> {
            Character character = characterMapper.selectById(m.getCharacterId());
            Map<String, Object> m1 = new LinkedHashMap<>();
            m1.put("id", m.getId());
            m1.put("characterId", m.getCharacterId());
            m1.put("characterName", character != null ? character.getName() : "角色#" + m.getCharacterId());
            m1.put("summary", m.getSummary());
            m1.put("sourceMsgIds", m.getSourceMsgIds());
            m1.put("createdAt", m.getCreatedAt());
            return m1;
        }).toList();

        return Result.ok(result);
    }

    @Operation(summary = "获取记忆详情（含来源消息）")
    @GetMapping("/{id}")
    public Result<Map<String, Object>> detail(@PathVariable Long id) {
        long userId = StpUtil.getLoginIdAsLong();

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

    @Operation(summary = "删除记忆")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        long userId = StpUtil.getLoginIdAsLong();

        MemoryMeta meta = memoryMetaMapper.selectOne(
                new LambdaQueryWrapper<MemoryMeta>()
                        .eq(MemoryMeta::getId, id)
                        .eq(MemoryMeta::getUserId, userId));
        if (meta == null) {
            return Result.fail(404, "记忆不存在");
        }

        memoryWriter.deleteVectors(meta.getMilvusVecId() == null ? List.of() : List.of(meta.getMilvusVecId()));
        memoryMetaMapper.deleteById(id);
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
