package com.lianyu.service.tools.bridge;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class AgentToolArgumentsTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void promotesTaskAliasToInstruction() throws Exception {
        String json = AgentToolArguments.normalizeJson("{\"task\":\"播放水手\"}", mapper);
        JsonNode node = mapper.readTree(json);
        assertThat(node.path("instruction").asText()).isEqualTo("播放水手");
        assertThat(node.path("task").asText()).isEqualTo("播放水手");
    }

    @Test
    void wrapsPlainTextAsInstruction() throws Exception {
        String json = AgentToolArguments.normalizeJson("打开网易云搜水手", mapper);
        assertThat(mapper.readTree(json).path("instruction").asText()).isEqualTo("打开网易云搜水手");
    }

    @Test
    void keepsExistingInstruction() throws Exception {
        String json = AgentToolArguments.normalizeJson("{\"instruction\":\"ping\"}", mapper);
        assertThat(mapper.readTree(json).path("instruction").asText()).isEqualTo("ping");
    }

    @Test
    void previewTruncates() {
        String preview = AgentToolArguments.preview("x".repeat(200));
        assertThat(preview).endsWith("…");
        assertThat(preview.length()).isLessThanOrEqualTo(161);
    }
}
