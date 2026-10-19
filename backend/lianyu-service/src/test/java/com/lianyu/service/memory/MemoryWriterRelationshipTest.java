package com.lianyu.service.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.lianyu.dao.entity.MemoryMeta;
import com.lianyu.dao.entity.Message;
import com.lianyu.dao.enums.MemoryType;
import com.lianyu.dao.mapper.MemoryMetaMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class MemoryWriterRelationshipTest {

    private final MemoryExtractionHeuristics heuristics = new MemoryExtractionHeuristics();

    @Test
    void extractRelationshipMemories_marksNicknameAsRitual() {
        Message msg = new Message();
        msg.setId(101L);
        msg.setRole("USER");
        msg.setContent("以后你可以叫我阿昼，这是只给你叫的。");

        List<MemoryExtractionHeuristics.HeuristicMemory> candidates =
                heuristics.extractRelationshipMemories(List.of(msg));

        MemoryExtractionHeuristics.HeuristicMemory ritual = candidates.stream()
                .filter(c -> c.memoryType() == MemoryType.RITUAL)
                .findFirst()
                .orElseThrow();
        assertTrue(ritual.summary().contains("专属称呼"));
    }

}
