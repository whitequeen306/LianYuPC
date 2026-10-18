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

    /** 纯比较：容易被单测覆盖，不需要网络。 */
    public static Diff compare(String javaPrompt, String pythonPrompt) {
        String left = javaPrompt == null ? "" : javaPrompt;
        String right = pythonPrompt == null ? "" : pythonPrompt;
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
        if (javaPrompt == null) {
            return null;
        }
        String response = client.postJson("/agent/prompt/assemble", userId, snapshot.toBody());
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
        }
        return diff;
    }

    /** 组装请求体（字段名与 Python 端 alias 一致）。 */
    public static Map<String, Object> bodyOf(AssemblySnapshot snapshot, long userId) {
        Map<String, Object> body = new LinkedHashMap<>(snapshot.toBody());
        body.put("userId", userId);
        return body;
    }
}
