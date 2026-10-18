package com.lianyu.service.memory;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lianyu.dao.entity.MemoryMeta;
import com.lianyu.dao.enums.MemoryType;
import com.lianyu.dao.mapper.MemoryMetaMapper;
import com.lianyu.service.ai.RerankerService;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 记忆块（预注入 system prompt 的结构化画像）golden。
 *
 * <p>`retrieveProfileContext` 的格式逻辑是纯的：按类型分四段
 * （用户画像 / 近期情绪线索 / 关系事件 / 专属仪式），段内 `- 摘要`，
 * 段间空行，全空返回 null。但它依赖 `loadContextMemoryMetas` 的取数顺序，
 * 所以这里用 mock mapper 固定数据，再让 Java 真实组装。
 *
 * <p>这一步迁的是「**格式**」。数据从哪来（Milvus/缓存/降级）下一步再对齐 ——
 * 先保证同样的记忆集合在两边产出同样的字符串。
 */
class MemoryProfileContextGoldenTest {

    private static MemoryMeta meta(long id, MemoryType type, String summary, String importance) {
        MemoryMeta m = new MemoryMeta();
        m.setId(id);
        m.setCharacterId(3L);
        m.setUserId(7L);
        m.setMemoryType(type);
        m.setSummary(summary);
        m.setImportance(new BigDecimal(importance));
        return m;
    }

    private static List<MemoryMeta> fixture() {
        List<MemoryMeta> rows = new ArrayList<>();
        rows.add(meta(1, MemoryType.FACT, "【长期记忆/姓名】小林", "0.90"));
        rows.add(meta(2, MemoryType.FACT, "【长期记忆/爱好】听雨声", "0.80"));
        rows.add(meta(3, MemoryType.EMOTION, "最近工作压力大", "0.70"));
        rows.add(meta(4, MemoryType.RELATION, "一起看过流星雨", "0.75"));
        rows.add(meta(5, MemoryType.RITUAL, "每晚互道晚安", "0.65"));
        rows.add(meta(6, MemoryType.FACT, "   ", "0.60"));           // 空白摘要应被忽略
        rows.add(meta(7, MemoryType.FACT, "【长期记忆/爱好】听雨声", "0.55")); // 重复应去重
        return rows;
    }

    @Test
    void dumpProfileContext() throws Exception {
        MemoryMetaMapper mapper = mock(MemoryMetaMapper.class);
        when(mapper.selectList(any())).thenReturn(fixture());
        MemoryCacheService cache = mock(MemoryCacheService.class);
        when(cache.getRecentRows(anyLong(), anyLong())).thenReturn(null);
        when(cache.getProfileFacts(anyLong(), anyLong())).thenReturn(null);

        MemoryRetriever retriever = new MemoryRetriever(
                mock(RerankerService.class),
                mock(MemoryVectorStore.class),
                mapper,
                cache);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("full", retriever.retrieveProfileContext(3L, 7L, "今天好累"));
        out.put("noQuery", retriever.retrieveProfileContext(3L, 7L));

        // 空数据分支
        MemoryMetaMapper emptyMapper = mock(MemoryMetaMapper.class);
        when(emptyMapper.selectList(any())).thenReturn(List.of());
        MemoryRetriever emptyRetriever = new MemoryRetriever(
                mock(RerankerService.class),
                mock(MemoryVectorStore.class),
                emptyMapper,
                mock(MemoryCacheService.class));
        out.put("empty", emptyRetriever.retrieveProfileContext(3L, 7L));

        Path target = Path.of("..", "..", "backend-agent", "app", "prompts",
                "profile_context_golden.json");
        Files.createDirectories(target.getParent());
        Files.writeString(target, new ObjectMapper().writerWithDefaultPrettyPrinter()
                .writeValueAsString(out) + "\n", StandardCharsets.UTF_8);
        System.out.println("profile context golden: full="
                + (out.get("full") == null ? "null" : "present")
                + ", empty=" + out.get("empty"));
    }
}
