package com.lianyu.service.voice;

import com.lianyu.service.ai.InnerThoughtFilter;
import java.util.ArrayList;
import java.util.List;

/**
 * 把 LLM 的增量文本切成**可立即合成的片段**。
 *
 * <p>为什么单独一个类：语音通话的体感由「第一句话什么时候出声」决定。要做到
 * 「上一句在播、下一句已经在合成」，就必须在 LLM 还在吐字的时候就开始切句、合成，
 * 而不是等整段回复结束。这段逻辑是纯文本算法，抽出来才能用单测钉住行为
 * （原来的实现埋在 {@code VoiceCallDuplexSession} 里，只能靠读代码确认）。
 *
 * <p>切分规则：
 * <ul>
 *   <li>模型显式标注的 {@code <|pause|>} 是最高优先级切点</li>
 *   <li>否则认句末强标点（。！？!? 换行）；**逗号不算切点**（切开会让语气断裂）</li>
 *   <li>不完整的 marker 前缀（如半个 {@code <|})永不切开</li>
 *   <li><b>首句</b>只要求 4 字就切 —— 首包延迟优先</li>
 *   <li><b>首句之后</b>要求积累到 {@value #MIN_LATER_COMMIT} 字再切 —— 音质优先，
 *       见下</li>
 *   <li><b>括号内不切</b>：内心独白形如 {@code （他笑了。真好）}，若在括号内切开，
 *       后续按句剥离括号内容就会残留半句并被念出来</li>
 * </ul>
 *
 * <h2>为什么首句之后要「攒够再切」</h2>
 *
 * 2026-10-22 线上实测：同一次合成里，句号模型的自然停顿是 <b>585ms</b>；把同一段文本
 * 拆成两句分别合成再拼回去，只剩 <b>304ms</b>（前句尾 175ms + 后句头 129ms），
 * 而且每句语调都重新起头。用户听到的就是「该停顿的地方连读、语气平淡」。
 *
 * <p>所以策略是**自适应的**：首句仍然按句切（此时用户还在等，延迟最重要）；
 * 首句之后把相邻短句合并成一个合成单元，让模型自己产出句间韵律，同时减少
 * TTS 往返次数（每一轮都要一次 TLS + 一次上游首包，约 1-1.5s）。
 *
 * <p>每个片段还带 {@link Segment#pauseAfterMs}：合成单元之间由调用方插入等长静音，
 * 把「合并后仍是两个单元」的边界补到与模型自然停顿相当。
 */
public final class SentenceSplitter {

    /** 角色模型自主标记的停顿点：语音 prompt 指示其在需要换气/停顿处输出。 */
    static final String PAUSE_TOKEN = "<|pause|>";

    private static final String STRONG_ENDERS = "。！？!?\n";
    private static final String WEAK_ENDERS = "；;…";

    /** 首句至少这么长才切，避免「嗯。」「好。」这种碎片。 */
    static final int MIN_FIRST_COMMIT = 4;

    /**
     * 首句之后，攒到这么长才切。
     *
     * <p>取 20 的取舍：更短 → 停顿碎、语调起头多、TTS 往返多；更长 → 第二片就绪太晚，
     * 首片播完后可能接不上（首片约 1.5-3s 音频，而单片合成 1-2s）。
     */
    static final int MIN_LATER_COMMIT = 20;

    /** 模型显式 pause 标记处：比句号更长的停顿。 */
    static final int PAUSE_MARKER_MS = 400;

    /** 句号级停顿：实测模型自然停顿 585ms - 两句自带的首尾静音 304ms ≈ 280ms。 */
    static final int SENTENCE_PAUSE_MS = 280;

    /** 分号级停顿。 */
    static final int CLAUSE_PAUSE_MS = 220;

    /** 逗号等弱边界被切开时的停顿（正常不会切，兜底用）。 */
    static final int SOFT_PAUSE_MS = 160;

    private final StringBuilder buffer = new StringBuilder();
    private boolean firstCommitSent;

    /**
     * 一个可合成片段。
     *
     * @param text         送进 TTS 的文本（marker 与内心独白已剥掉）
     * @param pauseAfterMs 这个片段之后应插多长的静音；回合最后一片为 0
     */
    public record Segment(String text, int pauseAfterMs) {
    }

    /** 喂一段 LLM 增量，返回这段之后**已经完整、可以立刻合成**的片段（可能为空）。 */
    public List<Segment> accept(String delta) {
        if (delta != null && !delta.isEmpty()) {
            buffer.append(delta);
        }
        List<Segment> segments = new ArrayList<>(2);
        while (true) {
            dropLeadingPauseTokens();
            int cut = nextCutOutsideParens(buffer.toString(), firstCommitSent);
            if (cut <= 0) {
                break;
            }
            boolean markerCut = buffer.indexOf(PAUSE_TOKEN) == cut;
            String piece = buffer.substring(0, cut);
            buffer.delete(0, cut);
            firstCommitSent = true;
            addClean(segments, piece, markerCut ? PAUSE_MARKER_MS : pauseForEnding(piece));
        }
        return segments;
    }

    /** 回合结束：把剩下的文本整段作为最后一片吐出（可能为空；之后不再需要停顿）。 */
    public List<Segment> flush() {
        List<Segment> segments = new ArrayList<>(1);
        dropLeadingPauseTokens();
        if (buffer.length() > 0) {
            addClean(segments, buffer.toString(), 0);
            buffer.setLength(0);
        }
        firstCommitSent = false;
        return segments;
    }

    /**
     * 清掉开头的 pause 标记。
     *
     * <p>必要性：切在 marker 处时，marker 本身留在缓冲区头部，下一轮
     * {@code indexOf(PAUSE_TOKEN) == 0} 会命中「marker 在 0 位」的分支而**永久不切**，
     * 该回合后续全部堆到 flush 才合成。这是按句流水线里一处真实的卡顿源。
     */
    private void dropLeadingPauseTokens() {
        while (buffer.indexOf(PAUSE_TOKEN) == 0) {
            buffer.delete(0, PAUSE_TOKEN.length());
        }
    }

    /** marker 与括号内心独白都不进 TTS、不进聊天记录。 */
    private static void addClean(List<Segment> out, String raw, int pauseAfterMs) {
        String text = InnerThoughtFilter.strip(stripPauseMarkers(raw));
        if (!text.isBlank()) {
            out.add(new Segment(text, pauseAfterMs));
        }
    }

    /** 按片段末尾标点决定停顿长度。 */
    static int pauseForEnding(String piece) {
        if (piece == null) {
            return 0;
        }
        for (int i = piece.length() - 1; i >= 0; i--) {
            char c = piece.charAt(i);
            if (Character.isWhitespace(c) && c != '\n') {
                continue;
            }
            if (STRONG_ENDERS.indexOf(c) >= 0) {
                return SENTENCE_PAUSE_MS;
            }
            if (WEAK_ENDERS.indexOf(c) >= 0) {
                return CLAUSE_PAUSE_MS;
            }
            return SOFT_PAUSE_MS;
        }
        return 0;
    }

    static String stripPauseMarkers(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        return s.replace(PAUSE_TOKEN, "").replace("<|", "");
    }

    /**
     * 找下一个**不在括号内**的切点。
     *
     * <p>为什么要跳过而不是放弃：{@code （他笑了。真好）我在。} 里第一个句号在括号内，
     * 如果就此不切，这一整段都会等到 flush 才合成 —— 首句延迟白白变大。
     * 正确做法是跳过括号内那个候选，继续找括号闭合后的下一个。
     *
     * <p>跳过之后不再受「最小长度」约束：{@code from > 0} 说明前面已经有足够的字了。
     */
    static int nextCutOutsideParens(String buf, boolean firstCommitSent) {
        int minChars = firstCommitSent ? MIN_LATER_COMMIT : MIN_FIRST_COMMIT;
        int from = 0;
        while (from < buf.length()) {
            int cut = nextCommitEnd(buf.substring(from), from == 0 ? minChars : 0);
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

    /** 兼容旧签名的入口：{@code firstCommitSent} 决定最小长度门槛。 */
    static int nextCommitEnd(String buf, boolean firstCommitSent) {
        return nextCommitEnd(buf, firstCommitSent ? MIN_LATER_COMMIT : MIN_FIRST_COMMIT);
    }

    /**
     * 合成边界决策（完全状态化）：只认两种停顿 —— 模型显式 {@link #PAUSE_TOKEN} 和句末强标点。
     * 取消字数硬切 / 逗号兜底；未完整 marker 前缀永不被切开。
     *
     * @param minChars 切点之前至少要有这么多字（首句 4、后续 20），0 表示不限制
     * @return 可切位置（不含），-1 表示暂不切
     */
    static int nextCommitEnd(String buf, int minChars) {
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
        int strong = indexOfAnyFrom(buf, STRONG_ENDERS, minChars);
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
