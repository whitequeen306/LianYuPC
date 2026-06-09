package com.lianyu.service.memory;

import com.lianyu.dao.entity.Message;
import com.lianyu.dao.enums.MemoryType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class MemoryExtractionHeuristics {

    public record ProfileFact(String slot, String value, Long sourceMsgId) {}

    public record HeuristicMemory(String summary, MemoryType memoryType, Long sourceMsgId, double importance) {}

    public List<HeuristicMemory> extractAll(List<Message> messages) {
        List<HeuristicMemory> result = new ArrayList<>();
        result.addAll(extractProfileFacts(messages));
        result.addAll(extractRelationshipMemories(messages));
        result.addAll(extractKeywordMemories(messages));
        return result;
    }

    public Set<Long> coveredMessageIds(List<HeuristicMemory> heuristicMemories) {
        Set<Long> ids = new LinkedHashSet<>();
        for (HeuristicMemory memory : heuristicMemories) {
            if (memory.sourceMsgId() != null) {
                ids.add(memory.sourceMsgId());
            }
        }
        return ids;
    }

    public List<HeuristicMemory> extractProfileFacts(List<Message> messages) {
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
        LinkedHashMap<String, ProfileFact> latestBySlot = new LinkedHashMap<>();
        for (ProfileFact fact : facts) {
            latestBySlot.put(fact.slot(), fact);
        }
        List<HeuristicMemory> memories = new ArrayList<>();
        for (ProfileFact fact : latestBySlot.values()) {
            memories.add(new HeuristicMemory(
                    profilePrefix(fact.slot()) + fact.value(),
                    MemoryType.FACT,
                    fact.sourceMsgId(),
                    0.8));
        }
        return memories;
    }

    public List<HeuristicMemory> extractRelationshipMemories(List<Message> messages) {
        List<HeuristicMemory> result = new ArrayList<>();
        for (Message msg : messages) {
            if (!"USER".equalsIgnoreCase(msg.getRole()) || msg.getContent() == null) {
                continue;
            }
            String text = msg.getContent().trim();
            if (text.isEmpty()) {
                continue;
            }
            if (text.contains("只给你叫") || text.contains("以后你可以叫我") || text.contains("专属")) {
                result.add(new HeuristicMemory("你们形成了专属称呼锚点", MemoryType.RITUAL, msg.getId(), 0.75));
            }
            if (text.contains("我今天很崩溃") || text.contains("我有点难受") || text.contains("我有点害怕")
                    || text.contains("其实我很") || text.contains("我真的很累")) {
                result.add(new HeuristicMemory("用户向你暴露了脆弱情绪", MemoryType.EMOTION, msg.getId(), 0.7));
            }
            if (text.contains("对不起") || text.contains("我解释一下") || text.contains("我不是故意的")) {
                result.add(new HeuristicMemory("用户尝试修复刚才的关系波动", MemoryType.RELATION, msg.getId(), 0.7));
            }
            if (text.contains("约定") || text.contains("答应你") || text.contains("以后我们")) {
                result.add(new HeuristicMemory("你们之间建立了一个小约定", MemoryType.RELATION, msg.getId(), 0.7));
            }
        }
        return result;
    }

    public List<HeuristicMemory> extractKeywordMemories(List<Message> messages) {
        List<HeuristicMemory> result = new ArrayList<>();
        for (Message msg : messages) {
            if (!"USER".equalsIgnoreCase(msg.getRole()) || msg.getContent() == null) {
                continue;
            }
            String text = msg.getContent().trim();
            if (text.length() < 4 || text.endsWith("?") || text.endsWith("？")) {
                continue;
            }
            if (containsAny(text, List.of("我叫", "我是", "我来自", "我工作", "职业是", "我的", "我姓"))) {
                result.add(new HeuristicMemory(clipped(text), MemoryType.FACT, msg.getId(), 0.75));
            } else if (containsAny(text, List.of("我喜欢", "我讨厌", "我爱吃", "我不爱吃", "我最爱", "不喜欢", "最爱"))) {
                result.add(new HeuristicMemory(clipped(text), MemoryType.FACT, msg.getId(), 0.7));
            } else if (containsAny(text, List.of("开心", "难过", "生气", "感动", "惊喜", "伤心", "快乐", "郁闷", "焦虑", "兴奋"))) {
                result.add(new HeuristicMemory(clipped(text), MemoryType.EMOTION, msg.getId(), 0.7));
            } else if (containsAny(text, List.of("今天", "昨天", "明天", "上周", "下周", "刚才", "周末", "假期"))) {
                result.add(new HeuristicMemory(clipped(text), MemoryType.RELATION, msg.getId(), 0.6));
            } else if (containsAny(text, List.of("我经常", "我习惯", "我通常", "我每天", "我总是", "我平常"))) {
                result.add(new HeuristicMemory(clipped(text), MemoryType.FACT, msg.getId(), 0.6));
            } else if (containsAny(text, List.of("我朋友", "我家人", "我父母", "我同事", "我老板", "我对象", "我男", "我女"))) {
                result.add(new HeuristicMemory(clipped(text), MemoryType.RELATION, msg.getId(), 0.6));
            }
        }
        return result;
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

    public boolean isValidFactValue(String fullText, String value) {
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
        return !t.endsWith("?") && !t.endsWith("？");
    }

    private String profilePrefix(String slot) {
        return "【长期记忆/" + slot + "】";
    }

    private String normalizeFactValue(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ");
    }

    private boolean containsAny(String text, List<String> keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private String clipped(String text) {
        String trimmed = text.trim();
        return trimmed.length() > 120 ? trimmed.substring(0, 120) : trimmed;
    }
}
