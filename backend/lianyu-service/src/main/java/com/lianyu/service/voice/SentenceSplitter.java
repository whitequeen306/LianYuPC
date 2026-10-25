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

    /**
     * 模型显式标注的**语义断句点**：这里换一个合成单元（新的一句从新调起）。
     *
     * <p>和 {@link #PAUSE_TOKEN} 的区别在意图：pause 是"同一句话里喘口气"，
     * cut 是"这句说完了，下一句另起"。两者都切单元，但 cut 不受 28 字门槛约束 ——
     * 模型说该断就断，短句也不许被合并。
     */
    static final String CUT_TOKEN = "<|cut|>";

    /**
     * 模型显式标注的**连读点**：放在标点之后，表示"这里不要断开"。
     *
     * <p>为什么需要零宽的"反向标记"：切分器只认标点和长度，遇到
     * {@code 我……我真的没事。} 这种会按标点切开，听感上是两口气；
     * 模型知道这里该一口气说完，就可以在标点后打 join 抑制切分。
     */
    static final String JOIN_TOKEN = "<|join|>";

    /** 所有 marker 里最长的长度：字幕剥离时用它判断"尾部是不是半截 marker"。 */
    static final int MAX_MARKER_LEN =
            Math.max(PAUSE_TOKEN.length(), Math.max(CUT_TOKEN.length(), JOIN_TOKEN.length()));

    private static final String STRONG_ENDERS = "。！？!?\n";

    /** 弱边界：只在**首片**上认它，用来抢首声延迟。见 {@link #MIN_FIRST_SOFT}。 */
    private static final String SOFT_ENDERS = "，、；：,;:";

    /** 首句至少这么长才切，避免「嗯。」「好。」这种碎片。 */
    static final int MIN_FIRST_COMMIT = 4;

    /**
     * 首片在**弱边界**（逗号类）上就能切的最短长度。
     *
     * <p>为什么首片要破例：首声延迟 = LLM 出第一个字（0.8-1.3s）+ 等句号 + TTS 首包（~0.9s）。
     * 如果模型开头是一个很长的句子（只在末尾才有句号），"等句号"这一段能吃掉 0.5-1.5s。
     * 中文口语里逗号本身就是自然的气口，在 8 字之后切开，听感损失很小，但能提前开口。
     *
     * <p>为什么只对首片：后续片段已经用 {@link #MIN_LATER_COMMIT} 合并成大片（音质优先），
     * 在那里按逗号切会把停顿切碎，得不偿失。
     */
    static final int MIN_FIRST_SOFT = 8;

    /**
     * 首句之后，攒到这么长才切。
     *
     * <p>取 28 的取舍：这个长度大约 4-6 秒语音，通常已经包含 1-2 个句号，**句间停顿交给模型
     * 自己生成**（实测模型自然停顿 585ms，比我们插的静音自然）。更短 → 边界多、停顿机械、
     * TTS 往返多；更长 → 第二片就绪太晚，首片播完后可能接不上（首片 1.5-3s 音频，
     * 单片合成热态 0.3-1.0s，还有余量）。
     */
    static final int MIN_LATER_COMMIT = 28;

    /**
     * 片段之间补多长的静音 —— **基准值是听感标定出来的，不是估的**。
     *
     * <p>2026-10-22 第一版按"模型自然停顿 585ms − 拼接后 304ms = 280ms"取 340ms，
     * 上线后用户仍觉得停顿不像真人。做了一次可控实验（同一段话，1/2/3 个合成单元）：
     *
     * <pre>
     *   1 个单元（整段合成）        7.76s
     *   2 个单元 + 不补静音         8.32s   ← 光是切成两句就多出 560ms
     *   2 个单元 + 340ms            8.66s
     *   3 个单元 + 340ms            9.24s
     * </pre>
     *
     * <p>也就是说**每片的自带首尾静音（304-560ms，随句尾标点波动）已经比模型内部
     * 跨句停顿还长**，再叠 340ms 会让个别边界到 1.5 秒 —— 真人一句话接一句话时中间
     * 很少有 1.5 秒。所以基准降到 150ms，其余按比例。
     */
    static final int SENTENCE_PAUSE_MS = 150;

    /** 感叹号 / 问号：语气收束更重，停得更久一点。 */
    static final int EXCLAIM_PAUSE_MS = 190;

    /** 省略号 / 破折号：本身就是"话没说完的沉默"。 */
    static final int ELLIPSIS_PAUSE_MS = 260;

    /** 分号：句内分层。 */
    static final int CLAUSE_PAUSE_MS = 110;

    /** 逗号等弱边界被切开时的停顿（正常不会切，兜底用）。 */
    static final int SOFT_PAUSE_MS = 90;

    /** 模型显式 pause 标记处：比句号更长的停顿。 */
    static final int PAUSE_MARKER_MS = 180;

    /**
     * 模型显式 cut（语义断句）后的停顿：比句号更长一些。
     *
     * <p>理由：模型只在"这句真说完了、下一句另起"时才打 cut，那是一次完整换气；
     * 而句号是排版意义上的句末，未必是口头停顿。两者区分开，模型的话才有分量。
     */
    static final int CUT_MARKER_MS = 260;

    private final StringBuilder buffer = new StringBuilder();
    private boolean firstCommitSent;

    /** 匹配三种朗读控制标记（大小写/内部空格容错）。 */
    private static final java.util.regex.Pattern MARKER_PATTERN = java.util.regex.Pattern.compile(
            "<\\|\\s*(pause|cut|join)\\s*\\|>",
            java.util.regex.Pattern.CASE_INSENSITIVE);

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
            // 归一化大小写变体，后面才能用 indexOf 精确命中
            buffer.append(normalizeMarkers(delta));
        }
        List<Segment> segments = new ArrayList<>(2);
        while (true) {
            dropLeadingMarkers();
            int cut = nextCutOutsideParens(buffer.toString(), firstCommitSent);
            if (cut <= 0) {
                break;
            }
            boolean pauseCut = buffer.indexOf(PAUSE_TOKEN) == cut;
            boolean semanticCut = buffer.indexOf(CUT_TOKEN) == cut;
            String piece = buffer.substring(0, cut);
            buffer.delete(0, cut);
            firstCommitSent = true;
            int pause = semanticCut ? CUT_MARKER_MS
                    : (pauseCut ? PAUSE_MARKER_MS : pauseForEnding(piece));
            addClean(segments, piece, pause);
        }
        return segments;
    }

    /** 回合结束：把剩下的文本整段作为最后一片吐出（可能为空；之后不再需要停顿）。 */
    public List<Segment> flush() {
        List<Segment> segments = new ArrayList<>(1);
        dropLeadingMarkers();
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
    private void dropLeadingMarkers() {
        // 三种标记都要清：任意一种停在 0 位都会命中 nextCommitEnd 的
        // "marker == 0 → return -1" 分支，导致该回合后续全部堆到 flush 才合成。
        boolean dropped = true;
        while (dropped) {
            dropped = false;
            for (String token : new String[] {CUT_TOKEN, PAUSE_TOKEN, JOIN_TOKEN}) {
                if (buffer.indexOf(token) == 0) {
                    buffer.delete(0, token.length());
                    dropped = true;
                }
            }
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
            if (c == ' ' || c == '\t' || c == '　') {
                continue;
            }
            if (c == '。' || c == '\n') {
                return SENTENCE_PAUSE_MS;
            }
            if (c == '！' || c == '？' || c == '!' || c == '?') {
                return EXCLAIM_PAUSE_MS;
            }
            if (c == '…' || c == '—' || c == '～' || c == '~') {
                return ELLIPSIS_PAUSE_MS;
            }
            if (c == '；' || c == ';') {
                return CLAUSE_PAUSE_MS;
            }
            return SOFT_PAUSE_MS;
        }
        return 0;
    }

    /** 剥掉全部朗读标记（含半截前缀）。跨包可用：非双工路径也要在落库前清干净。 */
    public static String stripPauseMarkers(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        // 先按完整标记剥（大小写不敏感，模型偶尔会吐 <|PAUSE|>），
        // 再清掉残留的 "<|" 前缀片段 —— 顺序不能反，否则 <|cut|> 会剩下 "cut|>"
        String cleaned = MARKER_PATTERN.matcher(s).replaceAll("");
        // 收尾处若残留半截标记（回合正好在标记中途结束），从 "<|" 起整段丢，
        // 否则会把 "cu" 这种碎片念出来
        int tail = cleaned.lastIndexOf("<|");
        if (tail >= 0 && cleaned.length() - tail <= MAX_MARKER_LEN) {
            cleaned = cleaned.substring(0, tail);
        }
        return cleaned.replace("<|", "");
    }

    /**
     * 只剥**完整**标记，保留半截前缀（{@code "<|cu"}）。
     *
     * <p>字幕流式下发必须用这个：一个标记可能被切在两个 delta 里，若在这里连
     * 半截前缀一起清掉，字幕就会**少字**；正确做法是把尾巴留到下一个 delta 再定。
     */
    static String stripCompleteMarkers(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        return MARKER_PATTERN.matcher(s).replaceAll("");
    }

    /** 把任何大小写/空格变体的 marker 归一成规范形式，供 indexOf 精确匹配。 */
    static String normalizeMarkers(String s) {
        if (s == null || s.isEmpty() || s.indexOf("<|") < 0) {
            return s == null ? "" : s;
        }
        return MARKER_PATTERN.matcher(s).replaceAll(
                m -> "<|" + m.group(1).toLowerCase(java.util.Locale.ROOT) + "|>");
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
        // 弱边界只在「首片的第一个切点」上生效
        boolean allowSoft = !firstCommitSent;
        int from = 0;
        while (from < buf.length()) {
            int cut = nextCommitEnd(buf.substring(from), from == 0 ? minChars : 0,
                    allowSoft && from == 0);
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

    /** 兼容旧签名的入口：{@code firstCommitSent} 决定最小长度门槛与是否认弱边界。 */
    static int nextCommitEnd(String buf, boolean firstCommitSent) {
        return nextCommitEnd(buf, firstCommitSent ? MIN_LATER_COMMIT : MIN_FIRST_COMMIT,
                !firstCommitSent);
    }

    /** 兼容旧签名的入口：只认强标点。 */
    static int nextCommitEnd(String buf, int minChars) {
        return nextCommitEnd(buf, minChars, false);
    }

    /**
     * 合成边界决策。
     *
     * <p>优先级（先到先切）：
     *
     * <ol>
     *   <li><b>模型显式标记</b>：{@code <|cut|>}（语义断句）与 {@code <|pause|>}（句内喘息）。
     *       两者都**不受 minChars 约束** —— 模型明确说了要断，就不该被"攒够 28 字"合并掉，
     *       只要求 cut 前面至少有 {@value #MIN_CUT_TEXT} 个字，避免切出单字碎片。</li>
     *   <li><b>半截 marker</b>：必须在它之前切住，否则会被当正文念出来。</li>
     *   <li><b>句末强标点</b>，但被 {@code <|join|>} 抑制的候选要跳过（连读）。</li>
     *   <li>首片额外认逗号类弱边界（抢开口时间），同样尊重 join。</li>
     * </ol>
     *
     * @param minChars  切点之前至少要有这么多字（首片 4、后续 28），0 表示不限制
     * @param allowSoft 是否认弱边界（逗号类），只对首片开
     * @return 可切位置（不含），-1 表示暂不切
     */
    static int nextCommitEnd(String buf, int minChars, boolean allowSoft) {
        if (buf == null || buf.isEmpty()) {
            return -1;
        }
        // ① 模型标记 + 半截 marker：取最靠前的那个（谁先出现谁说了算）
        int partial = indexOfPartialMarker(buf);
        if (partial == 0) {
            // 半截 marker 卡在开头：等后续 delta 补齐（回合结束时由 flush 兜底）
            return -1;
        }
        int hard = minPositive(
                indexOfToken(buf, CUT_TOKEN, MIN_CUT_TEXT),
                indexOfToken(buf, PAUSE_TOKEN, 1),
                partial);
        if (hard > 0) {
            return hard;
        }
        // ② 句末强标点（跳过被 join 抑制的候选）
        int strong = indexOfAnyFrom(buf, STRONG_ENDERS, minChars);
        while (strong >= 0) {
            int cut = strong + 1;
            if (startsWithJoin(buf, cut)) {
                // 模型要求这里连读：跳过这个标点，去后面找下一个断点
                strong = indexOfAnyFrom(buf, STRONG_ENDERS, cut + JOIN_TOKEN.length());
                continue;
            }
            // 首片上如果更早处有逗号类气口，优先用它 —— 早 0.3-1s 开口，比"这一句完整"更值
            if (allowSoft) {
                int soft = indexOfSoft(buf, MIN_FIRST_SOFT, strong);
                if (soft > 0) {
                    return soft + 1;
                }
            }
            return cut;
        }
        if (allowSoft) {
            int soft = indexOfSoft(buf, MIN_FIRST_SOFT, buf.length());
            if (soft >= 0) {
                return soft + 1;
            }
        }
        return -1;
    }

    /**
     * cut 之前至少要有的正文字数。
     *
     * <p>只约束 cut、不约束 pause：pause 的既有语义允许在「嗯」这种单字后切
     * （那是口语里的自然喘息，切出来也不刺耳）；而 cut 是"这句说完了"，
     * 前面只有 1 个字时更可能是模型标错了位置，宁可不切。
     */
    static final int MIN_CUT_TEXT = 2;

    /** marker 的位置；前面正文不足 {@code minText} 字时视为不可切（返回 -1）。 */
    private static int indexOfToken(String buf, String token, int minText) {
        int idx = buf.indexOf(token);
        return idx >= minText ? idx : -1;
    }

    /**
     * 找**未完整**的 marker 前缀（{@code <|cu} 这种）。
     *
     * <p>为什么不能直接 {@code indexOf("<|")}：{@code <|join|>} 是完整标记，
     * 也是零宽的（不出声、不该在这里断）。若把它当成半截前缀切住，就会**正好在
     * 模型要求连读的地方断句**，与标记语义相反。
     *
     * @return 半截前缀的下标；0 表示卡在开头（等后续 delta）；-1 表示没有
     */
    private static int indexOfPartialMarker(String buf) {
        int i = buf.indexOf("<|");
        while (i >= 0) {
            if (buf.startsWith(CUT_TOKEN, i)
                    || buf.startsWith(PAUSE_TOKEN, i)
                    || buf.startsWith(JOIN_TOKEN, i)) {
                i = buf.indexOf("<|", i + 2);
                continue;
            }
            return i <= 0 ? 0 : i;
        }
        return -1;
    }

    /**
     * 在 {@code [from, limit)} 里找最靠前的、**未被 join 抑制**的弱边界下标。
     *
     * @return 弱边界字符下标，找不到返回 -1
     */
    private static int indexOfSoft(String buf, int from, int limit) {
        int i = indexOfAnyFrom(buf, SOFT_ENDERS, from);
        while (i >= 0 && i < limit) {
            if (!startsWithJoin(buf, i + 1)) {
                return i;
            }
            i = indexOfAnyFrom(buf, SOFT_ENDERS, i + 1 + JOIN_TOKEN.length());
        }
        return -1;
    }

    /** {@code pos} 处是否紧跟连读标记（零宽，不产生停顿）。 */
    private static boolean startsWithJoin(String buf, int pos) {
        return pos >= 0 && pos + JOIN_TOKEN.length() <= buf.length()
                && buf.startsWith(JOIN_TOKEN, pos);
    }

    private static int minPositive(int... values) {
        int best = Integer.MAX_VALUE;
        for (int v : values) {
            if (v >= 0 && v < best) {
                best = v;
            }
        }
        return best == Integer.MAX_VALUE ? -1 : best;
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
