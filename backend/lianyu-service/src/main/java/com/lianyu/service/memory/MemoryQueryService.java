package com.lianyu.service.memory;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.lianyu.common.base.Result;
import com.lianyu.dao.entity.Character;
import com.lianyu.dao.entity.Conversation;
import com.lianyu.dao.entity.MemoryEpisode;
import com.lianyu.dao.entity.MemoryMeta;
import com.lianyu.dao.entity.Message;
import com.lianyu.dao.mapper.CharacterMapper;
import com.lianyu.dao.mapper.ConversationMapper;
import com.lianyu.dao.mapper.MemoryEpisodeMapper;
import com.lianyu.dao.mapper.MemoryMetaMapper;
import com.lianyu.dao.mapper.MessageMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 用户侧记忆查询/删除（从 MemoryController 下沉，docs/refactor-2026-10/refactor-plan.md §11（原耦合审查项 11））：
 * 查询组装、越权过滤与 Milvus 向量清理属于业务逻辑，Controller 只留协议壳。
 */
@Service
@RequiredArgsConstructor
public class MemoryQueryService {

    private static final int MAX_LIST_SIZE = 200;
    private static final int MAX_EPISODE_SIZE = 100;

    private final MemoryMetaMapper memoryMetaMapper;
    private final MemoryEpisodeMapper memoryEpisodeMapper;
    private final MessageMapper messageMapper;
    private final CharacterMapper characterMapper;
    private final ConversationMapper conversationMapper;
    private final MemoryWriter memoryWriter;
    private final MemoryCacheService memoryCacheService;

    /**
     * 共同经历列表：星图之外给人看的那一层。
     *
     * <p>原话（quote）一定带出来——只给摘要的话，用户没法确认"她记住的是不是我真说的那句"。
     */
    public Result<List<Map<String, Object>>> episodes(long userId, Long characterId, int limit) {
        int safeLimit = Math.min(MAX_EPISODE_SIZE, Math.max(1, limit));

        LambdaQueryWrapper<MemoryEpisode> q = new LambdaQueryWrapper<MemoryEpisode>()
                .eq(MemoryEpisode::getUserId, userId)
                .eq(MemoryEpisode::getStatus, "active")
                .orderByDesc(MemoryEpisode::getEventTime);
        if (characterId != null) {
            q.eq(MemoryEpisode::getCharacterId, characterId);
        }
        q.last("LIMIT " + safeLimit);

        List<MemoryEpisode> episodes = memoryEpisodeMapper.selectList(q);
        Set<Long> characterIds = episodes.stream()
                .map(MemoryEpisode::getCharacterId)
                .filter(id -> id != null && id > 0)
                .collect(Collectors.toSet());
        Map<Long, Character> characterMap = characterIds.isEmpty()
                ? Map.of()
                : characterMapper.selectBatchIds(characterIds).stream()
                        .collect(Collectors.toMap(Character::getId, c -> c, (a, b) -> a));

        List<Map<String, Object>> result = episodes.stream().map(e -> {
            Character character = characterMap.get(e.getCharacterId());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", e.getId());
            row.put("characterId", e.getCharacterId());
            row.put("characterName", character != null ? character.getName() : "角色#" + e.getCharacterId());
            row.put("summary", e.getSummary());
            row.put("quote", e.getQuote());
            row.put("eventTime", e.getEventTime());
            row.put("valence", e.getValence());
            row.put("arousal", e.getArousal());
            row.put("openLoop", e.getOpenLoop());
            row.put("createdAt", e.getCreatedAt());
            return row;
        }).toList();

        return Result.ok(result);
    }

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
            putMemoryFields(m1, m);
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
        putMemoryFields(result, meta);
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

    /**
     * 钉住/取消钉住：钉住 = 免衰减（tau_days=0）且强度回满。
     * 「别忘了这件事」不该靠用户反复提醒，也不能把行复制一份成 core —— 同一行加个开关最省。
     */
    public Result<Void> setPinned(long userId, Long id, boolean pinned) {
        MemoryMeta meta = memoryMetaMapper.selectOne(
                new LambdaQueryWrapper<MemoryMeta>()
                        .eq(MemoryMeta::getId, id)
                        .eq(MemoryMeta::getUserId, userId));
        if (meta == null) {
            return Result.fail(404, "记忆不存在");
        }

        // 用 UpdateWrapper 而不是 updateById：MyBatis-Plus 默认忽略 null 字段，
        // 那样取消钉住时 archived_at 永远清不掉。
        UpdateWrapper<MemoryMeta> patch = new UpdateWrapper<MemoryMeta>()
                .eq("id", id)
                .set("pinned", pinned);
        if (pinned) {
            patch.set("tau_days", 0)
                    .set("strength", BigDecimal.ONE)
                    .set("status", "active")
                    .set("fade_level", "active")
                    .set("archived_at", null);
        } else {
            boolean core = "core".equalsIgnoreCase(meta.getLayer());
            patch.set("tau_days", core ? 0 : 365);
        }
        memoryMetaMapper.update(null, patch);
        memoryCacheService.invalidate(userId, meta.getCharacterId());
        return Result.ok();
    }

    /**
     * muted 只影响"要不要主动提"：检索候选会排除 muted 行，但记忆页仍能看到、能搜到。
     * 与删除的区别——用户说的是"别再提了"，不是"当没发生过"。
     */
    public Result<Void> setMuted(long userId, Long id, boolean muted) {
        MemoryMeta meta = memoryMetaMapper.selectOne(
                new LambdaQueryWrapper<MemoryMeta>()
                        .eq(MemoryMeta::getId, id)
                        .eq(MemoryMeta::getUserId, userId));
        if (meta == null) {
            return Result.fail(404, "记忆不存在");
        }
        memoryMetaMapper.update(null, new UpdateWrapper<MemoryMeta>()
                .eq("id", id)
                .set("muted", muted));
        memoryCacheService.invalidate(userId, meta.getCharacterId());
        return Result.ok();
    }

    /**
     * 让她想起来：用户手动把沉底/归档的记忆拉回来。
     *
     * <p>只抬强度与档位、重置衰减起点，不改摘要——"想起来"不是"改写回忆"。
     * dormant 的行也允许复活：留着行就是为了这一天。
     */
    public Result<Void> revive(long userId, Long id) {
        MemoryMeta meta = memoryMetaMapper.selectOne(
                new LambdaQueryWrapper<MemoryMeta>()
                        .eq(MemoryMeta::getId, id)
                        .eq(MemoryMeta::getUserId, userId));
        if (meta == null) {
            return Result.fail(404, "记忆不存在");
        }
        memoryMetaMapper.update(null, new UpdateWrapper<MemoryMeta>()
                .eq("id", id)
                .eq("user_id", userId)
                .set("strength", new BigDecimal("0.55"))
                .set("fade_level", "active")
                .set("status", "active")
                .set("archived_at", null)
                .set("last_decay_at", LocalDateTime.now())
                .setSql("revival_count = revival_count + 1"));
        memoryCacheService.invalidate(userId, meta.getCharacterId());
        return Result.ok();
    }

    public Result<Void> delete(long userId, Long id) {
        MemoryMeta meta = memoryMetaMapper.selectOne(
                new LambdaQueryWrapper<MemoryMeta>()
                        .eq(MemoryMeta::getId, id)
                        .eq(MemoryMeta::getUserId, userId));
        if (meta == null) {
            return Result.fail(404, "记忆不存在");
        }

        // 删除意图与删行同事务落库：行删了但向量没删会导致「幽灵记忆」仍被检索到
        memoryWriter.deleteVectorForMemory(id, meta.getMilvusVecId());
        memoryMetaMapper.deleteById(id);
        memoryCacheService.invalidate(userId, meta.getCharacterId());
        return Result.ok();
    }

    /**
     * 列表与详情共用一份字段口径：前端星图靠 memoryType 分扇区、靠 fadeLevel
     * 画"模糊"，少一个字段就会整片退化成同一种颜色。
     */
    private void putMemoryFields(Map<String, Object> target, MemoryMeta meta) {
        target.put("summary", meta.getSummary());
        target.put("memoryType", meta.getMemoryType() == null ? null : meta.getMemoryType().name());
        target.put("importance", meta.getImportance());
        target.put("sourceMsgIds", meta.getSourceMsgIds());
        target.put("createdAt", meta.getCreatedAt());
        target.put("layer", meta.getLayer());
        target.put("strength", meta.getStrength());
        target.put("fadeLevel", meta.getFadeLevel());
        target.put("pinned", meta.getPinned());
        target.put("muted", meta.getMuted());
        target.put("status", meta.getStatus());
        target.put("eventTime", meta.getEventTime());
        target.put("quote", meta.getQuote());
        target.put("revision", meta.getRevision());
        target.put("lastRecalledAt", meta.getLastRecalledAt());
        target.put("recallCount", meta.getRecallCount());
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
