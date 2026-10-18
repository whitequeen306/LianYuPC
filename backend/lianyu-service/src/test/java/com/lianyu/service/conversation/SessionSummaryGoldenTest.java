package com.lianyu.service.conversation;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 会话摘要块 golden（纯格式：标题 + 摘要 + 说明）。
 *
 * <p>覆盖：正常摘要、首尾带空白的摘要（Java 会 trim）、空串 / 纯空白（返回 null）。
 */
class SessionSummaryGoldenTest {

    @Test
    void dumpSessionSummaryGolden() throws Exception {
        Map<String, Object> cases = new LinkedHashMap<>();
        cases.put("normal", SessionSummaryService.formatPrompt("用户聊到最近工作压力大，角色安慰了他"));
        cases.put("padded", SessionSummaryService.formatPrompt("  前后有空格  "));
        cases.put("empty", SessionSummaryService.formatPrompt(""));
        cases.put("blank", SessionSummaryService.formatPrompt("   \n  "));

        Path target = Path.of("..", "..", "backend-agent", "app", "prompts",
                "session_summary_golden.json");
        Files.createDirectories(target.getParent());
        Files.writeString(target, new ObjectMapper().writerWithDefaultPrettyPrinter()
                .writeValueAsString(cases) + "\n", StandardCharsets.UTF_8);
        System.out.println("session summary golden: " + cases.size());
    }
}