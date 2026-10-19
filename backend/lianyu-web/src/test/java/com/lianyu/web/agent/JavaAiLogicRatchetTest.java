package com.lianyu.web.agent;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * 「Java 零 AI 逻辑」的**棘轮测试**（ratchet）：只允许变少，不允许变多。
 *
 * <p>为什么用测试而不是脚本：`mvn test` 本来就在 CI 里跑，不需要额外接线。
 * 脚本（`scripts/check-java-ai-free.ps1`）保留给人看明细。
 *
 * <p>棘轮的用法：每次迁移把某类代码删掉后，把下面的 BASELINE 调小；
 * 如果谁在 Java 里新加了一段 prompt 文本或模型调用，这里立刻失败。
 */
class JavaAiLogicRatchetTest {

    /** 迁移基线（只允许下降）。 */
    private static final int BASELINE_PROMPT_TEXTS = 10;
    private static final int BASELINE_MODEL_CALLS = 26;
    private static final int BASELINE_AI_CLASSES = 9;

    /** 允许保留：三条基础设施 seam。 */
    private static final Set<String> ALLOWED_FILES = Set.of(
            "ApiKeyVaultService.java",
            "JasyptUtil.java",
            "AgentBridgeService.java",
            "AgentBridgeController.java",
            "ClientBridgeToolCallback.java");

    private static final List<String> PROMPT_MARKERS = List.of(
            "=== 关于用户的记忆", "=== 当前情绪状态", "=== 安全与边界", "=== 可用工具",
            "回复规则：", "表达要求：", "当前心情：", "状态签名：", "上一刻心情：",
            "你是「", "{{MENTION}}", "{{NAME}}");

    private static final List<Pattern> MODEL_CALLS = List.of(
            Pattern.compile("aiChatService\\.chat"),
            Pattern.compile("\\.chatBlocking\\("),
            Pattern.compile("\\.chatStream\\("),
            Pattern.compile("OpenAiChatModel"),
            Pattern.compile("OllamaChatModel"),
            Pattern.compile("ChatClient\\.builder"),
            Pattern.compile("springframework\\.ai\\.chat\\.model"));

    private static final Set<String> KNOWN_AI_CLASSES = Set.of(
            "ChatTurnPromptAssembler.java", "CharacterPromptBuilder.java",
            "MemoryRetriever.java", "RelationshipStateService.java",
            "CharacterStateService.java", "SessionSummaryService.java",
            "SessionSummaryMerger.java", "MemoryLlmExtractor.java", "ToolManager.java",
            "PromptsRuleEngine.java", "LanguageGateService.java");

    /** 从测试模块出发找到 backend 根（lianyu-web 的上一级）。 */
    private static Path backendRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null) {
            if (Files.isDirectory(current.resolve("lianyu-service"))
                    && Files.isDirectory(current.resolve("lianyu-web"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("找不到 backend 根目录");
    }

    private static List<Path> javaSources() throws IOException {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(backendRoot())) {
            stream.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> path.toString().contains("src" + java.io.File.separator + "main"))
                    .filter(path -> !ALLOWED_FILES.contains(path.getFileName().toString()))
                    .forEach(files::add);
        }
        return files;
    }

    /** 可单测的计数：证明棘轮真的能发现问题（否则它只是个摆设）。 */
    static int countPromptTexts(java.util.List<String> lines) {
        int hits = 0;
        for (String line : lines) {
            for (String marker : PROMPT_MARKERS) {
                if (line.contains(marker)) {
                    hits++;
                    break;
                }
            }
        }
        return hits;
    }

    @Test
    void ratchetDetectsNewPromptText() {
        // 防空转：如果有人新写了一段 prompt 文本，这里必须能数出来
        org.junit.jupiter.api.Assertions.assertEquals(0,
                countPromptTexts(java.util.List.of("private void foo() {", "}")));
        org.junit.jupiter.api.Assertions.assertEquals(1,
                countPromptTexts(java.util.List.of("String s = \"=== 安全与边界 ===\";")));
        org.junit.jupiter.api.Assertions.assertEquals(2,
                countPromptTexts(java.util.List.of("回复规则：", "当前心情：")));
    }

    @Test
    void promptTextsDoNotGrow() throws IOException {
        int hits = 0;
        List<String> offenders = new ArrayList<>();
        for (Path file : javaSources()) {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                for (String marker : PROMPT_MARKERS) {
                    if (lines.get(i).contains(marker)) {
                        hits++;
                        offenders.add(file.getFileName() + ":" + (i + 1));
                        break;
                    }
                }
            }
        }
        assertTrue(hits <= BASELINE_PROMPT_TEXTS,
                "Java 里的 prompt 文本从 " + BASELINE_PROMPT_TEXTS + " 增到 " + hits
                        + "；新增处：" + offenders.stream().limit(10).toList());
    }

    @Test
    void modelCallsDoNotGrow() throws IOException {
        int hits = 0;
        List<String> offenders = new ArrayList<>();
        for (Path file : javaSources()) {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                for (Pattern pattern : MODEL_CALLS) {
                    if (pattern.matcher(lines.get(i)).find()) {
                        hits++;
                        offenders.add(file.getFileName() + ":" + (i + 1));
                        break;
                    }
                }
            }
        }
        assertTrue(hits <= BASELINE_MODEL_CALLS,
                "Java 里的模型调用从 " + BASELINE_MODEL_CALLS + " 增到 " + hits
                        + "；新增处：" + offenders.stream().limit(10).toList());
    }

    @Test
    void knownAiClassesDoNotGrow() throws IOException {
        int hits = (int) javaSources().stream()
                .filter(path -> KNOWN_AI_CLASSES.contains(path.getFileName().toString()))
                .count();
        assertTrue(hits <= BASELINE_AI_CLASSES,
                "已知 AI 类从 " + BASELINE_AI_CLASSES + " 增到 " + hits);
    }

    @Test
    void seamFilesAreStillAllowed() {
        assertTrue(ALLOWED_FILES.contains("ApiKeyVaultService.java"));
        assertTrue(ALLOWED_FILES.contains("AgentBridgeService.java"));
    }
}
