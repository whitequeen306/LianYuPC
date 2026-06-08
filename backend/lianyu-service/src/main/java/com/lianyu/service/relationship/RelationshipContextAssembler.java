package com.lianyu.service.relationship;

import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

public class RelationshipContextAssembler {

    public String assemble(RelationshipSnapshot snapshot, List<String> recentEvents) {
        String phase = snapshot.phase().name().toLowerCase(Locale.ROOT).replace('_', '-');
        String trust = RelationshipPromptLabels.describe("trust", snapshot.trustScore());
        String intimacy = RelationshipPromptLabels.describe("intimacy", snapshot.intimacyScore());
        String security = RelationshipPromptLabels.describe("security", snapshot.securityScore());
        String anticipation = RelationshipPromptLabels.describe("anticipation", snapshot.anticipationScore());
        String eventBlock = recentEvents == null || recentEvents.isEmpty()
                ? "- 无"
                : recentEvents.stream().map(it -> "- " + it).collect(Collectors.joining("\n"));
        return "关系阶段: " + phase + "\n"
                + "trust: " + trust + "\n"
                + "intimacy: " + intimacy + "\n"
                + "security: " + security + "\n"
                + "anticipation: " + anticipation + "\n"
                + "近期关系事件:\n" + eventBlock;
    }
}
