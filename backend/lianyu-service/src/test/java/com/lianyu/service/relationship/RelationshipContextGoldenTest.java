package com.lianyu.service.relationship;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 关系块 golden：四维分数标签 + 阶段 + 事件列表的完整拼装。
 *
 * <p>分数标签是**分档映射**（>=75 / >=50 / >=25 / else），边界值最容易错一位，
 * 所以除整体拼装外，还导出 `describe` 在 0..100 全部取值上的表，
 * Python 直接按表比对 —— 404 条标签一次性锁死，不靠抽样。
 */
class RelationshipContextGoldenTest {

    private static RelationshipContextAssembler assembler() {
        return new RelationshipContextAssembler();
    }

    @Test
    void dumpRelationshipGolden() throws Exception {
        RelationshipContextAssembler assembler = assembler();
        Map<String, String> cases = new LinkedHashMap<>();

        int[] scores = {0, 24, 25, 49, 50, 74, 75, 100};
        int index = 0;
        for (RelationshipPhase phase : RelationshipPhase.values()) {
            for (int score : scores) {
                RelationshipSnapshot snapshot = RelationshipSnapshot.builder()
                        .trustScore(score)
                        .intimacyScore(100 - score)
                        .securityScore(score)
                        .anticipationScore(100 - score)
                        .phase(phase)
                        .build();
                cases.put(phase.name() + "|" + score,
                        assembler.assemble(snapshot, List.of("一起看过流星雨", "吵过一次架")));
                index++;
            }
        }
        // 空事件分支
        RelationshipSnapshot emptyEvents = RelationshipSnapshot.builder()
                .trustScore(60).intimacyScore(60).securityScore(60).anticipationScore(60)
                .phase(RelationshipPhase.values()[0])
                .build();
        cases.put("empty-events", assembler.assemble(emptyEvents, List.of()));

        // 标签表（4 维 × 0..100）
        Map<String, Map<String, String>> labels = new LinkedHashMap<>();
        for (String dimension : new String[]{"trust", "intimacy", "security", "anticipation"}) {
            Map<String, String> band = new LinkedHashMap<>();
            for (int score = 0; score <= 100; score++) {
                band.put(String.valueOf(score), RelationshipPromptLabels.describe(dimension, score));
            }
            labels.put(dimension, band);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cases", cases);
        out.put("labels", labels);
        out.put("phases", java.util.Arrays.stream(RelationshipPhase.values())
                .map(Enum::name).toList());

        Path target = Path.of("..", "..", "backend-agent", "app", "prompts",
                "relationship_golden.json");
        Files.createDirectories(target.getParent());
        Files.writeString(target, new ObjectMapper().writerWithDefaultPrettyPrinter()
                .writeValueAsString(out) + "\n", StandardCharsets.UTF_8);
        System.out.println("relationship golden: cases=" + cases.size()
                + ", label dims=" + labels.size() + ", index=" + index);
    }
}
