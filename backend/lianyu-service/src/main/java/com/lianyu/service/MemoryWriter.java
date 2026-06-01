package com.lianyu.service;

import com.lianyu.dao.entity.MemoryMeta;
import com.lianyu.dao.entity.Message;
import com.lianyu.dao.mapper.MemoryMetaMapper;
import com.lianyu.dao.mapper.MessageMapper;
import com.lianyu.storage.milvus.MilvusConfig;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.MutationResult;
import io.milvus.param.dml.DeleteParam;
import io.milvus.param.dml.InsertParam;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

import java.io.Serializable;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryWriter {

    private static final String EXCHANGE = "lianyu.exchange";
    private static final String ROUTING_KEY = "memory.summary";

    private final MessageMapper messageMapper;
    private final MemoryMetaMapper memoryMetaMapper;
    private final EmbeddingService embeddingService;
    private final MilvusServiceClient milvusClient;
    private final RabbitTemplate rabbitTemplate;

    public void enqueueSummary(Long conversationId, Long characterId, Long userId) {
        MemorySummaryTask task = new MemorySummaryTask(conversationId, characterId, userId);
        rabbitTemplate.convertAndSend(EXCHANGE, ROUTING_KEY, task);
        log.info("Memory summary enqueued: conversationId={}, characterId={}", conversationId, characterId);
    }

    public void processSummary(MemorySummaryTask task) {
        try {
            List<Message> recentMsgs = messageMapper.selectList(
                    new LambdaQueryWrapper<Message>()
                            .eq(Message::getConversationId, task.conversationId())
                            .orderByDesc(Message::getSeq)
                            .last("LIMIT 30"));

            if (recentMsgs.isEmpty()) {
                return;
            }

            java.util.Collections.reverse(recentMsgs);
            List<ProfileFact> facts = extractProfileFacts(recentMsgs);
            if (facts.isEmpty()) {
                log.debug("Skip memory write: no profile facts, convId={}", task.conversationId());
                return;
            }

            int created = 0;
            int updated = 0;
            int skipped = 0;
            for (ProfileFact fact : facts) {
                MemoryUpsertResult result = upsertProfileMemory(task, List.of(fact.sourceMsgId()), fact);
                switch (result) {
                    case CREATED -> created++;
                    case UPDATED -> updated++;
                    case SKIPPED -> skipped++;
                }
            }

            log.info("Memory upsert done: convId={}, created={}, updated={}, skipped={}",
                    task.conversationId(), created, updated, skipped);
        } catch (Exception e) {
            log.error("Memory processing failed for conversation {}", task.conversationId(), e);
            // 抛出给 Rabbit listener，由容器决定重试/入死信
            throw new RuntimeException("memory summary process failed, convId=" + task.conversationId(), e);
        }
    }

    public void deleteVectors(List<String> vectorIds) {
        if (vectorIds == null || vectorIds.isEmpty()) {
            return;
        }
        List<String> ids = vectorIds.stream()
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .toList();
        if (ids.isEmpty()) {
            return;
        }
        try {
            String expr = ids.stream()
                    .map(id -> "id == " + id)
                    .reduce((left, right) -> left + " or " + right)
                    .orElse(null);
            if (expr == null || expr.isBlank()) {
                return;
            }
            milvusClient.delete(DeleteParam.newBuilder()
                    .withCollectionName(MilvusConfig.COLLECTION_MEMORY_VECTORS)
                    .withExpr(expr)
                    .build());
        } catch (Exception e) {
            log.warn("Milvus delete failed: {}", e.getMessage());
        }
    }

    private MemoryUpsertResult upsertProfileMemory(MemorySummaryTask task, List<Long> sourceIds, ProfileFact fact) {
        String sourceHash = computeFactHash(task.userId(), task.characterId(), fact.slot());
        String summary = formatProfileSummary(fact.slot(), fact.value());
        MemoryMeta existing = findExistingProfileMemory(task.userId(), task.characterId(), fact.slot(), sourceHash);

        Message factMsg = messageMapper.selectById(fact.sourceMsgId());
        if (existing != null && factMsg != null && isStaleFact(existing, factMsg)) {
            log.debug("Skip stale profile fact: slot={}, value={}, msgId={}",
                    fact.slot(), fact.value(), fact.sourceMsgId());
            return MemoryUpsertResult.SKIPPED;
        }

        if (existing == null) {
            String vecId = insertVector(task.characterId(), task.userId(), summary);
            MemoryMeta meta = new MemoryMeta();
            meta.setCharacterId(task.characterId());
            meta.setUserId(task.userId());
            meta.setSummary(summary);
            meta.setSourceMsgIds(sourceIds);
            meta.setSourceHash(sourceHash);
            meta.setMilvusVecId(vecId);
            memoryMetaMapper.insert(meta);
            return MemoryUpsertResult.CREATED;
        }

        String oldValue = parseProfileValue(existing.getSummary());
        if (oldValue != null && normalizeFactValue(oldValue).equals(normalizeFactValue(fact.value()))) {
            existing.setSourceMsgIds(mergeSourceIds(existing.getSourceMsgIds(), sourceIds));
            if (!sourceHash.equals(existing.getSourceHash())) {
                existing.setSourceHash(sourceHash);
            }
            memoryMetaMapper.updateById(existing);
            return MemoryUpsertResult.SKIPPED;
        }

        String oldVecId = existing.getMilvusVecId();
        existing.setSummary(summary);
        existing.setSourceMsgIds(mergeSourceIds(existing.getSourceMsgIds(), sourceIds));
        existing.setSourceHash(sourceHash);
        existing.setMilvusVecId(insertVector(task.characterId(), task.userId(), summary));
        memoryMetaMapper.updateById(existing);
        deleteVectors(oldVecId == null ? List.of() : List.of(oldVecId));
        return MemoryUpsertResult.UPDATED;
    }

    private MemoryMeta findExistingProfileMemory(Long userId, Long characterId, String slot, String sourceHash) {
        MemoryMeta byHash = memoryMetaMapper.selectOne(
                new LambdaQueryWrapper<MemoryMeta>()
                        .eq(MemoryMeta::getSourceHash, sourceHash)
                        .last("LIMIT 1"));
        if (byHash != null) {
            return byHash;
        }

        List<MemoryMeta> bySlot = memoryMetaMapper.selectList(
                new LambdaQueryWrapper<MemoryMeta>()
                        .eq(MemoryMeta::getUserId, userId)
                        .eq(MemoryMeta::getCharacterId, characterId)
                        .likeRight(MemoryMeta::getSummary, profilePrefix(slot))
                        .orderByDesc(MemoryMeta::getCreatedAt));
        if (bySlot.isEmpty()) {
            return null;
        }

        MemoryMeta primary = bySlot.get(0);
        for (int i = 1; i < bySlot.size(); i++) {
            MemoryMeta duplicate = bySlot.get(i);
            deleteVectors(duplicate.getMilvusVecId() == null ? List.of() : List.of(duplicate.getMilvusVecId()));
            memoryMetaMapper.deleteById(duplicate.getId());
        }
        return primary;
    }

    private boolean isStaleFact(MemoryMeta existing, Message factMsg) {
        Message latestStored = getLatestSourceMessage(existing.getSourceMsgIds());
        return latestStored != null && factMsg.getCreatedAt().isBefore(latestStored.getCreatedAt());
    }

    private Message getLatestSourceMessage(List<Long> sourceMsgIds) {
        if (sourceMsgIds == null || sourceMsgIds.isEmpty()) {
            return null;
        }
        List<Message> messages = messageMapper.selectList(
                new LambdaQueryWrapper<Message>()
                        .in(Message::getId, sourceMsgIds)
                        .orderByDesc(Message::getCreatedAt)
                        .last("LIMIT 1"));
        return messages.isEmpty() ? null : messages.get(0);
    }

    private String insertVector(Long characterId, Long userId, String summary) {
        try {
            float[] vector = embeddingService.embed(summary, userId);
            List<List<Float>> vectors = new ArrayList<>();
            List<Float> floatList = new ArrayList<>(vector.length);
            for (float f : vector) {
                floatList.add(f);
            }
            vectors.add(floatList);

            List<InsertParam.Field> fields = List.of(
                    new InsertParam.Field("character_id", List.of(characterId)),
                    new InsertParam.Field("user_id", List.of(userId)),
                    new InsertParam.Field("vector", vectors)
            );

            InsertParam insertParam = InsertParam.newBuilder()
                    .withCollectionName(MilvusConfig.COLLECTION_MEMORY_VECTORS)
                    .withFields(fields)
                    .build();

            MutationResult mr = milvusClient.insert(insertParam).getData();
            if (mr != null) {
                return String.valueOf(mr.getIDs().getIntId().getData(0));
            }
        } catch (Exception e) {
            log.warn("Milvus insert failed: {}", e.getMessage());
        }
        return null;
    }

    private List<ProfileFact> extractProfileFacts(List<Message> messages) {
        List<ProfileFact> facts = new ArrayList<>();
        for (Message msg : messages) {
            if (!"USER".equalsIgnoreCase(msg.getRole()) || msg.getContent() == null) {
                continue;
            }
            String text = msg.getContent().trim();
            if (text.isEmpty()) {
                continue;
            }
            matchAndAdd(facts, msg.getId(), "姓名", text, "我叫([\\p{L}A-Za-z0-9_·]{1,20})");
            matchAndAdd(facts, msg.getId(), "姓名", text, "我是([\\p{L}A-Za-z0-9_·]{1,20})");
            matchAndAdd(facts, msg.getId(), "姓名", text, "现在(?:我)?叫([\\p{L}A-Za-z0-9_·]{1,20})");
            matchAndAdd(facts, msg.getId(), "姓名", text, "改名(?:叫|为|成)([\\p{L}A-Za-z0-9_·]{1,20})");
            matchAndAdd(facts, msg.getId(), "姓名", text, "名字(?:是|叫)([\\p{L}A-Za-z0-9_·]{1,20})");
            matchAndAdd(facts, msg.getId(), "爱好", text, "我喜欢([^，。！？；\\n]{1,30})");
            matchAndAdd(facts, msg.getId(), "忌口", text, "我不吃([^，。！？；\\n]{1,30})");
            matchAndAdd(facts, msg.getId(), "忌口", text, "我对([^，。！？；\\n]{1,30})过敏");
            matchAndAdd(facts, msg.getId(), "偏好", text, "我更喜欢([^，。！？；\\n]{1,30})");
            matchAndAdd(facts, msg.getId(), "禁忌", text, "不要让我([^，。！？；\\n]{1,30})");
        }
        // 按 slot 去重，保留最后一次（覆盖旧偏好）
        java.util.Map<String, ProfileFact> latestBySlot = new java.util.LinkedHashMap<>();
        for (ProfileFact fact : facts) {
            latestBySlot.put(fact.slot(), fact);
        }
        return new ArrayList<>(latestBySlot.values());
    }

    private void matchAndAdd(List<ProfileFact> facts, Long sourceMsgId, String slot, String text, String regex) {
        Matcher matcher = Pattern.compile(regex).matcher(text);
        while (matcher.find()) {
            String value = matcher.group(1);
            if (value != null && !value.isBlank()) {
                String normalized = normalizeFactValue(value);
                if (!normalized.isBlank() && isValidFactValue(text, normalized)) {
                    facts.add(new ProfileFact(slot, normalized, sourceMsgId));
                }
            }
        }
    }

    /**
     * 过滤提问句里的伪匹配，例如「我叫什么」「我喜欢什么」。
     */
    private boolean isValidFactValue(String fullText, String value) {
        String t = fullText == null ? "" : fullText.trim().toLowerCase();
        String v = value.trim().toLowerCase();
        if (v.isEmpty()) {
            return false;
        }
        String[] invalid = {"什么", "谁", "啥", "哪", "吗", "么", "呢"};
        for (String token : invalid) {
            if (v.equals(token) || v.contains(token)) {
                return false;
            }
        }
        if (t.endsWith("?") || t.endsWith("？")) {
            return false;
        }
        return true;
    }

    private String formatProfileSummary(String slot, String value) {
        return profilePrefix(slot) + value;
    }

    private String profilePrefix(String slot) {
        return "【长期记忆/" + slot + "】";
    }

    private String parseProfileValue(String summary) {
        if (summary == null) {
            return null;
        }
        int idx = summary.indexOf("】");
        if (idx >= 0 && idx + 1 < summary.length()) {
            return summary.substring(idx + 1).trim();
        }
        return summary.trim();
    }

    private List<Long> mergeSourceIds(List<Long> oldIds, List<Long> newIds) {
        Set<Long> merged = new LinkedHashSet<>();
        if (oldIds != null) {
            merged.addAll(oldIds);
        }
        if (newIds != null) {
            merged.addAll(newIds);
        }
        return new ArrayList<>(merged);
    }

    private String computeFactHash(Long userId, Long characterId, String slot) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(("u:" + userId + "|c:" + characterId + "|slot:" + slot).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    private String normalizeFactValue(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ");
    }

    private enum MemoryUpsertResult { CREATED, UPDATED, SKIPPED }
    private record ProfileFact(String slot, String value, Long sourceMsgId) {}

    public record MemorySummaryTask(Long conversationId, Long characterId,
                                     Long userId) implements Serializable {}
}
