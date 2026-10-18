package com.lianyu.web.agent;

import com.lianyu.service.agent.AssemblySnapshot;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * SHADOW 模式下的 **prompt 级**漂移比对。
 *
 * <p>为什么需要：`AgentShadowStats` 现在比的是「最终回复」，但 prompt 组装是回复的
 * 上游 —— 如果两边 prompt 不同，回复不同只是结果。只看回复长度差异无法回答
 * 「到底是 prompt 漂移了，还是模型随机性」。
 *
 * <p>关键约束：
 * <ul>
 *   <li>**异步且不阻塞**：在独立线程池执行，失败只记 WARN，绝不影响用户请求</li>
 *   <li>**不落原文**：只比对长度 + 首处差异位置 + 差异片段长度，
 *       日志里不出现 prompt 内容（含用户隐私与角色设定）</li>
 *   <li>只在 SHADOW / AGENT 模式执行；OFF 模式完全不发请求</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PromptShadowComparator {

    /** 差异摘要：不包含 prompt 原文。 */
    public record Diff(boolean equal, int javaLength, int pythonLength, int firstDiffAt) {
        public String describe() {
            if (equal) {
                return "match(" + javaLength + " chars)";
            }
            return "DIFF java=" + javaLength + " py=" + pythonLength + " at=" + firstDiffAt;
        }
    }

    private final AgentRuntimeClient client;
    private final AgentShadowStats shadowStats;

    /**
     * 掩掉**易变片段**再比对：时间事实（两边各读一次，跨分钟必然不同）。
     *
     * <p>不掩掉的话，每分钟边界都会产生一次假差异，漂移率会虚高且无法解释。
     * 掩码只替换时间行本身，不改变结构 —— 真正的结构差异仍会被发现。
     */
    public static String maskVolatile(String prompt) {
        if (prompt == null || prompt.isEmpty()) {
            return prompt == null ? "" : prompt;
        }
        return prompt
                .replaceAll("(?m)^当前真实时间：.*$", "当前真实时间：<masked>")
                .replaceAll("(?m)^当前时段：.*$", "当前时段：<masked>");
    }

    /** 纯比较：容易被单测覆盖，不需要网络。 */
    public static Diff compare(String javaPrompt, String pythonPrompt) {
        String left = maskVolatile(javaPrompt == null ? "" : javaPrompt);
        String right = maskVolatile(pythonPrompt == null ? "" : pythonPrompt);
        if (left.equals(right)) {
            return new Diff(true, left.length(), right.length(), -1);
        }
        int limit = Math.min(left.length(), right.length());
        int firstDiff = 0;
        while (firstDiff < limit && left.charAt(firstDiff) == right.charAt(firstDiff)) {
            firstDiff++;
        }
        return new Diff(false, left.length(), right.length(), firstDiff);
    }

    /**
     * 比对一次；由调用方保证已判断过模式。
     *
     * @return 比较结果；Python 不可用时返回 null
     */
    public Diff compareWithAgent(long userId, AssemblySnapshot snapshot, String javaPrompt) {
        return compareWithAgent(userId, snapshot, javaPrompt, false);
    }

    /**
     * @param reportSegments 为 true 时把分段结果写进日志（真实请求下用于定位）
     */
    public Diff compareWithAgent(long userId, AssemblySnapshot snapshot, String javaPrompt,
                                 boolean reportSegments) {
        if (javaPrompt == null) {
            return null;
        }
        String response = client.postJson("/agent/prompt/assemble", userId, bodyOf(snapshot, userId));
        if (response == null) {
            return null;
        }
        com.fasterxml.jackson.databind.JsonNode root = client.readTree(response);
        if (root.hasNonNull("error")) {
            log.debug("Prompt shadow skipped (agent error {}): userId={}",
                    root.path("error").asText(), userId);
            return null;
        }
        String pythonPrompt = root.path("systemPrompt").asText("");
        Diff diff = compare(javaPrompt, pythonPrompt);
        if (diff.equal()) {
            shadowStats.recordMatch("prompt");
        } else {
            shadowStats.recordDiff("prompt");
            log.warn("Prompt shadow[{}]: {}", userId, diff.describe());
            if (reportSegments) {
                java.util.List<String> bad = new java.util.ArrayList<>();
                for (SegmentDiff segment : segmentDiff(javaPrompt, pythonPrompt)) {
                    if (!segment.equal()) {
                        bad.add(segment.describe());
                    }
                }
                // 只打印「哪些段不同」，不含 prompt 正文
                log.warn("Prompt shadow[{}] segments DIFF: {}", userId, bad);
                // 段名序列（不含正文）：用于定位「多段/少段/顺序不同」
                java.util.List<String> javaNames = new java.util.ArrayList<>();
                for (java.util.Map.Entry<String, String> e : splitSegments(javaPrompt)) {
                    javaNames.add(e.getKey() + ":" + e.getValue().length());
                }
                java.util.List<String> pyNames = new java.util.ArrayList<>();
                for (java.util.Map.Entry<String, String> e : splitSegments(pythonPrompt)) {
                    pyNames.add(e.getKey() + ":" + e.getValue().length());
                }

                log.warn("Prompt shadow[{}] javaSegs={}", userId, javaNames);
                log.warn("Prompt shadow[{}] pySegs={}", userId, pyNames);
            }
        }
        return diff;
    }

    /** 分段差异：定位「哪一段不同」，而不只是「第几个字符不同」。 */
    public record SegmentDiff(String name, boolean equal, int javaChars, int pythonChars, int firstDiffAt) {
        public String describe() {
            return equal
                    ? name + " match(" + javaChars + ")"
                    : name + " DIFF java=" + javaChars + " py=" + pythonChars + " at=" + firstDiffAt;
        }
    }

    /** 段落分隔标记：以 "=== " 开头的行，或 "[...]" 形式的块标题。 */
    private static final java.util.regex.Pattern SEGMENT_HEADER =
            java.util.regex.Pattern.compile("^(=== .*?===\\s*$|\\[[^\\]]+\\]\\s*$)");

    /**
     * 按段落切分并逐段比对。
     *
     * <p>为什么需要：整串 diff 只能给出「第 172 个字符不同」，而排查时真正想知道的是
     * 「记忆块不一样，还是情绪块不一样」。分段后差异会直接落到具体块上。
     *
     * <p>切分只依赖**两侧共有的标题行**（`=== … ===` 或 `[标签]`），
     * 因此中英日不同语言的标题也能自然对齐 —— 只要两边用同一套模板。
     */
    public static java.util.List<SegmentDiff> segmentDiff(String javaPrompt, String pythonPrompt) {
        java.util.List<SegmentDiff> result = new java.util.ArrayList<>();
        java.util.List<java.util.Map.Entry<String, String>> left = splitSegments(javaPrompt);
        java.util.List<java.util.Map.Entry<String, String>> right = splitSegments(pythonPrompt);

        int count = Math.max(left.size(), right.size());
        for (int i = 0; i < count; i++) {
            java.util.Map.Entry<String, String> l = i < left.size() ? left.get(i) : null;
            java.util.Map.Entry<String, String> r = i < right.size() ? right.get(i) : null;
            String name = l != null ? l.getKey() : (r != null ? r.getKey() : "#" + i);
            String lv = l != null ? l.getValue() : "";
            String rv = r != null ? r.getValue() : "";
            Diff diff = compare(lv, rv);
            result.add(new SegmentDiff(name, diff.equal(), diff.javaLength(), diff.pythonLength(),
                    diff.firstDiffAt()));
        }
        return result;
    }

    /** 切分为 (标题, 正文) 列表；第一段的标题为 `<preamble>`。 */
    static java.util.List<java.util.Map.Entry<String, String>> splitSegments(String prompt) {
        java.util.List<java.util.Map.Entry<String, String>> segments = new java.util.ArrayList<>();
        String text = prompt == null ? "" : prompt;
        String currentName = "<preamble>";
        StringBuilder body = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            if (SEGMENT_HEADER.matcher(line.trim()).matches()) {
                segments.add(java.util.Map.entry(currentName, body.toString()));
                currentName = line.trim();
                body.setLength(0);
            } else {
                body.append(line).append('\n');
            }
        }
        segments.add(java.util.Map.entry(currentName, body.toString()));
        return segments;
    }

    private static String snippet(String text, int from, int len) {
        if (text == null) {
            return "";
        }
        int end = Math.min(text.length(), from + len);
        if (from >= end) {
            return "";
        }
        return text.substring(from, end).replace("\n", "\\n");
    }

    /** 组装请求体（字段名与 Python 端 alias 一致）。 */
    public static Map<String, Object> bodyOf(AssemblySnapshot snapshot, long userId) {
        Map<String, Object> body = new LinkedHashMap<>(snapshot.toBody());
        body.put("userId", userId);
        return body;
    }
}
