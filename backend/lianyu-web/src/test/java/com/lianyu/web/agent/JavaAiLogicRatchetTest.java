package com.lianyu.web.agent;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
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

    /** 终局基线：OFF 退役后 Java 零 AI 逻辑。 */
    private static final int BASELINE_PROMPT_TEXTS = 0;
    private static final int BASELINE_MODEL_CALLS = 0;
    private static final int BASELINE_AI_CLASSES = 0;

    /** 允许保留：基础设施 seam（加解密）。桌面能力层已于 2026-10-25 删除，白名单同步收窄。 */
    private static final Set<String> ALLOWED_FILES = Set.of(
            "ApiKeyVaultService.java",
            "JasyptUtil.java");

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
            Pattern.compile("springframework\\.ai\\.chat\\.model"),
            // 2026-10-20 补：TTS / 音色克隆 / 向量化 的出站路径。
            // 这几个模式就是「检查器 0/0/0 却仍有 EmbeddingService 在跑」那个盲区 ——
            // 只数 prompt 文本与 chat 调用，数不到「另一套 HTTP 自己发请求」。
            Pattern.compile("/api/v1/services/aigc/multimodal-generation"),
            Pattern.compile("/api/v1/services/audio/tts/customization"),
            Pattern.compile("/api/v1/services/rerank/"),
            Pattern.compile("/compatible-mode/v1/embeddings"));

    /** OFF 退役后所有已知 AI 类都已清理/迁移；留空表示清单彻底清零。 */
    private static final Set<String> KNOWN_AI_CLASSES = Set.of();

    /** 已迁往 Python 的能力：这些文件名不允许在 Java 里复活。 */
    private static final Set<String> MIGRATED_AI_CLIENT_FILES = Set.of(
            "DashScopeTtsService.java",
            "DashScopeVoiceEnrollmentClient.java",
            "EmbeddingService.java",
            "RerankerService.java");

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
    void migratedAiClientsStayDeleted() throws IOException {
        List<String> offenders = javaSources().stream()
                .map(path -> path.getFileName().toString())
                .filter(MIGRATED_AI_CLIENT_FILES::contains)
                .toList();
        assertTrue(offenders.isEmpty(),
                "这些能力已经迁到 Python，不许在 Java 复活：" + offenders);
    }

    @Test
    void seamFilesAreStillAllowed() {
        assertTrue(ALLOWED_FILES.contains("ApiKeyVaultService.java"));
        assertTrue(ALLOWED_FILES.contains("JasyptUtil.java"));
    }

    // ══════════════════════════════════════════════════════════════════════
    // 规则 5：裸 prompt 长文案（2026-10-26 补，A 批次迁移完成后启用）
    //
    // 为什么加这条：上面 PROMPT_MARKERS 是 12 个**固定标题**（`=== 关于用户的记忆` 等），
    // 只能认出「当年那几段」。A 批次 6 段、B/C 批次 18 段文案一直在 Java 里，
    // 检查器却报 0/0/0 —— 那是假阴性，不是通过。这里改成**结构特征**：
    //   ① `=== 标题 ===` 段头；② 三语 prompt 特征词 ≥2；③ 非 SQL 文本块 ≥60 字。
    // 命中的文案必须登记在 scripts/java_bare_prompt_allowlist.txt（带「待迁」说明），
    // 迁走之后把条目删掉 —— 清单只会变短，这就是棘轮。
    // ══════════════════════════════════════════════════════════════════════

    private static final int BARE_PROMPT_MIN_LEN = 60;

    /** prompt 特征词（三语）。只收「写成句子才会出现」的词：role / character 这类 SQL 列名不收。 */
    private static final List<String> BARE_PROMPT_MARKERS = List.of(
            "你是", "你正", "请你", "必须", "不要", "禁止", "输出", "格式", "任务",
            "场景", "扮演", "语气", "字数", "只输出", "用户", "角色", "回复",
            "あなた", "ください", "出力", "タスク", "ユーザー", "キャラクター", "口調", "返信",
            "You are", "Do not", "Don't", "Never", "You must");

    /** `=== 标题 ===` 段头：prompt 块最稳的结构特征，业务文案/SQL/日志里不会出现。 */
    private static final Pattern SECTION_HEADER = Pattern.compile("===\\s*\\S.*?===");

    private static final Pattern SQL_START = Pattern.compile(
            "(?is)^\\s*(<script|select|insert|update|delete|with)\\b");

    /** 角色资料数据（人设档案，不是 prompt 指令）：数据搬家不在本棘轮范围。 */
    private static final List<String> BARE_PROMPT_SKIP_FILES = List.of("CharacterSquareCatalog");

    /** 一个 Java 字面量：单行字符串或文本块。 */
    record Literal(int line, String text, boolean textBlock) {}

    private static Path repoRoot() {
        return backendRoot().getParent();
    }

    private static Path allowlistPath() {
        return repoRoot().resolve("scripts").resolve("java_bare_prompt_allowlist.txt");
    }

    /** 逐字符扫：跳过注释，抽出字符串与文本块（文本块跨行）。 */
    static List<Literal> extractLiterals(String src) {
        List<Literal> out = new ArrayList<>();
        int i = 0;
        int line = 1;
        int n = src.length();
        while (i < n) {
            char c = src.charAt(i);
            if (c == '\n') {
                line++;
                i++;
                continue;
            }
            if (c == '/' && i + 1 < n && src.charAt(i + 1) == '/') {
                while (i < n && src.charAt(i) != '\n') {
                    i++;
                }
                continue;
            }
            if (c == '/' && i + 1 < n && src.charAt(i + 1) == '*') {
                int end = src.indexOf("*/", i + 2);
                int stop = end < 0 ? n : end + 2;
                for (int k = i; k < stop; k++) {
                    if (src.charAt(k) == '\n') {
                        line++;
                    }
                }
                i = stop;
                continue;
            }
            if (src.startsWith("\"\"\"", i)) {
                int startLine = line;
                int end = src.indexOf("\"\"\"", i + 3);
                if (end < 0) {
                    break;
                }
                String body = src.substring(i + 3, end);
                line += (int) body.chars().filter(ch -> ch == '\n').count();
                out.add(new Literal(startLine, body, true));
                i = end + 3;
                continue;
            }
            if (c == '"') {
                int startLine = line;
                StringBuilder sb = new StringBuilder();
                int j = i + 1;
                while (j < n) {
                    char d = src.charAt(j);
                    if (d == '\\') {
                        sb.append(src, j, Math.min(j + 2, n));
                        j += 2;
                        continue;
                    }
                    if (d == '"') {
                        break;
                    }
                    if (d == '\n') {
                        line++;
                    }
                    sb.append(d);
                    j++;
                }
                out.add(new Literal(startLine, sb.toString(), false));
                i = j + 1;
                continue;
            }
            if (c == '\'') {
                int j = i + 1;
                while (j < n) {
                    if (src.charAt(j) == '\\') {
                        j += 2;
                        continue;
                    }
                    if (src.charAt(j) == '\'') {
                        break;
                    }
                    j++;
                }
                i = j + 1;
                continue;
            }
            i++;
        }
        return out;
    }

    /** 转义还原：只认 \\n / \\t（判断长度与特征词够用，不做完整 JLS 还原）。 */
    static String unescape(String raw) {
        return raw.replace("\\n", "\n").replace("\\t", "\t");
    }

    static boolean looksLikeBarePrompt(Literal lit) {
        String text = unescape(lit.text());
        if (text.length() < BARE_PROMPT_MIN_LEN) {
            return false;
        }
        if (SECTION_HEADER.matcher(text).find()) {
            return true;
        }
        int markers = 0;
        for (String marker : BARE_PROMPT_MARKERS) {
            if (text.contains(marker)) {
                markers++;
            }
        }
        if (markers >= 2) {
            return true;
        }
        // 文本块：业务代码里除了 SQL 几乎只有 prompt 写成文本块
        return lit.textBlock() && !SQL_START.matcher(text).find();
    }

    /** 指纹 = 相对路径 + 正文头 48 字（改文案就会变，逼你确认「是迁走还是更新登记」）。 */
    static String fingerprint(String relPath, Literal lit) {
        String flat = unescape(lit.text()).replaceAll("\\s+", " ").trim();
        String head = flat.length() > 48 ? flat.substring(0, 48) : flat;
        return relPath + "::" + head;
    }

    private static Set<String> loadAllowlist() throws IOException {
        Path path = allowlistPath();
        Set<String> entries = new LinkedHashSet<>();
        if (!Files.isRegularFile(path)) {
            return entries;
        }
        for (String raw : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            entries.add(line);
        }
        return entries;
    }

    @Test
    void barePromptDetectorIsNotBlind() {
        // 防空转：探针文案必须**真的过长度阈值**，否则断言靠「太短」通过，等于没测。
        assertTrue(looksLikeBarePrompt(new Literal(1,
                "你是「小助手」。必须遵守：不要解释过程，不要输出标题，回复用户时只用一句话，"
                        + "语气自然些，字数控制在三十字以内，这是任务要求。", false)));
        assertTrue(looksLikeBarePrompt(new Literal(1,
                "\n=== 新场景任务（系统指令 · 不是用户发来的消息）===\n"
                        + "请按上面的要求自然作答，不要逐条朗读，也不要复述这段说明本身。", false)));
        assertTrue(looksLikeBarePrompt(new Literal(1,
                "You are a群聊 judge. Do not explain. Reply with JSON only when needed.", false)));
        assertTrue(looksLikeBarePrompt(new Literal(1,
                "这是测试用的填充句子，".repeat(6), true)));

        // 反例同样要够长：短 SQL 会因为长度不足而通过，那样「SQL 不算 prompt」这条根本没被验证
        org.junit.jupiter.api.Assertions.assertFalse(looksLikeBarePrompt(new Literal(1,
                "SELECT id, username, role FROM admin_user WHERE role = ? AND status = 1", true)));
        org.junit.jupiter.api.Assertions.assertFalse(looksLikeBarePrompt(new Literal(1,
                "INSERT INTO admin_user_role(admin_user_id,role_id) VALUES(?,?) "
                        + "ON DUPLICATE KEY UPDATE role_id=role_id", true)));
        org.junit.jupiter.api.Assertions.assertFalse(looksLikeBarePrompt(new Literal(1,
                "Failed to decrypt with LIANYU_MASTER_KEY; ciphertext may use a different key", false)));
        org.junit.jupiter.api.Assertions.assertFalse(looksLikeBarePrompt(new Literal(1,
                "太短，不该报", false)));
    }

    @Test
    void barePromptLiteralsAreAllRegistered() throws IOException {
        Set<String> allowlist = loadAllowlist();
        List<String> violations = new ArrayList<>();
        Set<String> used = new LinkedHashSet<>();
        for (Path file : javaSources()) {
            String name = file.getFileName().toString();
            if (BARE_PROMPT_SKIP_FILES.stream().anyMatch(name::startsWith)) {
                continue;
            }
            String rel = repoRoot().relativize(file).toString().replace('\\', '/');
            for (Literal lit : extractLiterals(Files.readString(file, StandardCharsets.UTF_8))) {
                if (!looksLikeBarePrompt(lit)) {
                    continue;
                }
                String fp = fingerprint(rel, lit);
                if (allowlist.contains(fp)) {
                    used.add(fp);
                } else {
                    violations.add(fp + "   (行 " + lit.line() + ")");
                }
            }
        }
        assertTrue(violations.isEmpty(),
                "Java 里出现了未登记的 prompt 长文案 —— 文案应搬去 backend-agent/app/prompts/，"
                        + "确有原因留在 Java 的请登记进 scripts/java_bare_prompt_allowlist.txt：\n  "
                        + String.join("\n  ", violations));

        Set<String> stale = new LinkedHashSet<>(allowlist);
        stale.removeAll(used);
        assertTrue(stale.isEmpty(),
                "允许清单里这些条目已经失效 —— 已迁走的请删掉（棘轮只许变短），"
                        + "文案改过的请更新指纹：\n  " + String.join("\n  ", stale));
    }
}
