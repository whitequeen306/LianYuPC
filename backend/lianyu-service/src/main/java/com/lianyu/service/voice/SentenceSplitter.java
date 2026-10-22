package com.lianyu.service.voice;

import com.lianyu.service.ai.InnerThoughtFilter;
import java.util.ArrayList;
import java.util.List;

/**
 * 把 LLM 的增量文本切成**可立即合成的句子**。
 *
 * <p>为什么单独一个类：语音通话的体感由「第一句话什么时候出声」决定。要做到
 * 「上一句在播、下一句已经在合成」，就必须在 LLM 还在吐字的时候就开始切句、合成，
 * 而不是等整段回复结束。这段逻辑是纯文本算法，抽出来才能用单测钉住行为
 * （原来的实现埋在 {@code VoiceCallDuplexSession} 里，只能靠读代码确认）。
 *
 * <p>切分规则（沿用 2026-08 定下、已有单测覆盖的那套）：
 * <ul>
 *   <li>模型显式标注的 {@code <|pause|>} 是最高优先级切点</li>
 *   <li>否则认句末强标点（。！？!? 换行）；**逗号不算切点**（切开会让语气断裂）</li>
 *   <li>不完整的 marker 前缀（如半个 {@code <|})永不切开</li>
 *   <li>首次切分要求至少 4 字，避免「嗯。」这种一句一顿</li>
 *   <li><b>括号内不切</b>：内心独白形如 {@code （他笑了。真好）}，若在括号内切开，
 *       后续按句剥离括号内容就会残留半句并被念出来</li>
 * </ul>
 */
public final class SentenceSplitter {

    /** 角色模型自主标记的停顿点：语音 prompt 指示其在需要换气/停顿处输出。 */
    static final String PAUSE_TOKEN = "<|pause|>";

    private static final String STRONG_ENDERS = "。！？!?\n";
    /** 首句至少这么长才切，避免「嗯。」「好。」这种碎片。 */
    private static final int MIN_FIRST_COMMIT = 4;

    private final StringBuilder buffer = new StringBuilder();
    private boolean firstCommitSent;

    /** 喂一段 LLM 增量，返回这段之后**已经完整、可以立刻合成**的句子（可能为空）。 */
    public List<String> accept(String delta) {
        if (delta != null && !delta.isEmpty()) {
            buffer.append(delta);
        }
        List<String> sentences = new ArrayList<>(2);
        while (true) {
            int cut = nextCutOutsideParens(buffer.toString(), firstCommitSent);
            if (cut <= 0) {
                break;
            }
            String piece = buffer.substring(0, cut);
            buffer.delete(0, cut);
            firstCommitSent = true;
            addClean(sentences, piece);
        }
        return sentences;
    }

    /**
     * 找下一个**不在括号内**的切点。
     *
     * <p>为什么要跳过而不是放弃：{@code （他笑了。真好）我在。} 里第一个句号在括号内，
     * 如果就此不切，这一整段都会等到 flush 才合成 —— 首句延迟白白变大。
     * 正确做法是跳过括号内那个候选，继续找括号闭合后的下一个。
     */
    static int nextCutOutsideParens(String buf, boolean firstCommitSent) {
        int from = 0;
        while (from < buf.length()) {
            int cut = nextCommitEnd(buf.substring(from), firstCommitSent || from > 0);
            if (cut <= 0) {
                return -1;
            }
            int absolute = from + cut;
            if (!insideParens(buf, absolute - 1)) {
                return absolute;
            }
            from = absolute;
        }
        return -1;
    }

    /** 回合结束：把剩下的文本整段作为最后一句吐出（可能为空）。 */
    public List<String> flush() {
        List<String> sentences = new ArrayList<>(1);
        if (buffer.length() > 0) {
            addClean(sentences, buffer.toString());
            buffer.setLength(0);
        }
        firstCommitSent = false;
        return sentences;
    }

    /** marker 与括号内心独白都不进 TTS、不进聊天记录。 */
    private static void addClean(List<String> out, String raw) {
        String text = InnerThoughtFilter.strip(stripPauseMarkers(raw));
        if (!text.isBlank()) {
            out.add(text);
        }
    }

    static String stripPauseMarkers(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        return s.replace(PAUSE_TOKEN, "").replace("<|", "");
    }

    /**
     * 合成边界决策（完全状态化）：只认两种停顿 —— 模型显式 {@link #PAUSE_TOKEN} 和句末强标点。
     * 取消字数硬切 / 逗号兜底；长句（≥2 句）按句界切，短句（整段一次合成）。
     * 未完整 marker 前缀永不被切开。
     *
     * @return 可切位置（不含），-1 表示暂不切
     */
    static int nextCommitEnd(String buf, boolean firstCommitSent) {
        if (buf == null || buf.isEmpty()) {
            return -1;
        }
        int marker = buf.indexOf(PAUSE_TOKEN);
        if (marker > 0) {
            return marker;
        }
        if (marker == 0) {
            return -1;
        }
        int partial = buf.indexOf("<|");
        if (partial > 0) {
            return partial;
        }
        if (partial == 0) {
            return -1;
        }
        int strong = indexOfAnyFrom(buf, STRONG_ENDERS, firstCommitSent ? 0 : MIN_FIRST_COMMIT);
        if (strong >= 0) {
            return strong + 1;
        }
        return -1;
    }

    private static int indexOfAnyFrom(String buf, String chars, int fromIndex) {
        for (int i = Math.max(0, fromIndex); i < buf.length(); i++) {
            if (chars.indexOf(buf.charAt(i)) >= 0) {
                return i;
            }
        }
        return -1;
    }

    /** 下标 index 处是否位于未闭合的括号内（全角或半角）。 */
    static boolean insideParens(String buf, int index) {
        int depth = 0;
        int limit = Math.min(index + 1, buf.length());
        for (int i = 0; i < limit; i++) {
            char c = buf.charAt(i);
            if (c == '（' || c == '(') {
                depth++;
            } else if (c == '）' || c == ')') {
                depth = Math.max(0, depth - 1);
            }
        }
        return depth > 0;
    }
}
