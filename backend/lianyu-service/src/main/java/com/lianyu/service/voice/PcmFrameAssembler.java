package com.lianyu.service.voice;

import java.util.Arrays;

/**
 * 把任意切片的 PCM 字节流拼成**样本对齐**的定长帧。
 *
 * <p>为什么必须有这个类：上游（Python agent → WebClient 的 DataBuffer）给出的字节切片
 * 边界完全由 TCP/HTTP 分块决定，**与 16bit 采样边界无关**。原先直接「收到一块就发一帧
 * {@code tts.audio}」，于是出现两类故障，都会听起来像电子杂音：
 *
 * <ul>
 *   <li><b>起始偏移是奇数</b>：客户端对每帧独立地 {@code new Int16Array(buffer, offset, n)}
 *       解释，偏移错一个字节就等于把整帧的高低字节全部对调 —— 整帧变成高频噪声；</li>
 *   <li><b>长度是奇数</b>：{@code Math.floor(len / 2)} 直接丢掉最后半字节，
 *       帧尾就是一个台阶，听感是「啪」的一声。</li>
 * </ul>
 *
 * <p>这里保证对外发出的每一帧都是：起始于样本边界 + 长度为偶数字节 + 定长（默认 100ms），
 * 于是客户端哪怕什么都不改也不会再错位。定长还顺带减少帧数量：一帧 100ms 比原来
 * 动辄 60KB 的任意切片更容易被浏览器主线程消化，间接缓解「字幕/中间结果被音频帧挤后」。
 *
 * <p>**不跨句保留余数**：每句合成是独立的字节流，句末必须 {@link #flush} 把残留发完，
 * 否则半字节会串到下一句开头，重新制造错位。
 */
final class PcmFrameAssembler {

    /** 与 {@code app/tts.py} 的 STREAM_SAMPLE_RATE、前端 createPcmPlayback 默认值一致。 */
    static final int SAMPLE_RATE = 24000;

    static final int BYTES_PER_SAMPLE = 2;

    /** 一帧 100ms：足够小以保证低延迟，足够大以避免每帧一次 base64/解码开销。 */
    static final int FRAME_MS = 100;

    /** 4800 字节 = 24k × 2 字节 × 0.1s；偶数，天然是样本整数倍。 */
    static final int FRAME_BYTES = SAMPLE_RATE * BYTES_PER_SAMPLE * FRAME_MS / 1000;

    private final byte[] pending = new byte[FRAME_BYTES];
    private int pendingLen;

    /**
     * 喂入一段上游字节，凑满一帧就回调一帧。
     *
     * @param chunk 上游切片（长度、对齐都不保证）
     * @param emit  满帧回调；同一帧只回调一次
     */
    void push(byte[] chunk, FrameConsumer emit) {
        if (chunk == null || chunk.length == 0) {
            return;
        }
        int offset = 0;
        while (offset < chunk.length) {
            if (pendingLen == 0 && chunk.length - offset >= FRAME_BYTES) {
                // 空缓冲 + 剩余足够一帧：直接切，不经过 memcpy
                emit.accept(chunk, offset, FRAME_BYTES);
                offset += FRAME_BYTES;
                continue;
            }
            int take = Math.min(FRAME_BYTES - pendingLen, chunk.length - offset);
            System.arraycopy(chunk, offset, pending, pendingLen, take);
            pendingLen += take;
            offset += take;
            if (pendingLen == FRAME_BYTES) {
                emit.accept(pending, 0, FRAME_BYTES);
                pendingLen = 0;
            }
        }
    }

    /**
     * 句末收尾：把不足一帧的余数发出去。
     *
     * <p>余数是奇数字节时**丢掉最后一个字节**（半个样本，本来就不完整）；
     * 这样既不会发出错位帧，也不会把半字节带到下一句。
     */
    void flush(FrameConsumer emit) {
        if (pendingLen <= 0) {
            return;
        }
        int aligned = pendingLen - (pendingLen % BYTES_PER_SAMPLE);
        if (aligned > 0) {
            emit.accept(pending, 0, aligned);
        }
        pendingLen = 0;
    }

    int pendingBytes() {
        return pendingLen;
    }

    /** 生成一段静音（样本对齐）。用于句间停顿，见 {@code SENTENCE_GAP}。 */
    static byte[] silence(int millis) {
        if (millis <= 0) {
            return new byte[0];
        }
        int bytes = (SAMPLE_RATE * BYTES_PER_SAMPLE * millis) / 1000;
        bytes -= bytes % BYTES_PER_SAMPLE;
        return new byte[bytes];
    }

    /**
     * 回调只给 {@code (array, offset, length)}，由实现决定要不要复制 ——
     * 定长帧直接引用内部缓冲区时，接收方必须立刻复制（{@link #emitCopying}）。
     */
    @FunctionalInterface
    interface FrameConsumer {
        void accept(byte[] array, int offset, int length);
    }

    /** 需要持有字节（比如塞进 JSON 帧）时用这个包装，避免复用缓冲区被后续帧覆盖。 */
    static FrameConsumer emitCopying(FrameConsumer target) {
        return (array, offset, length) -> target.accept(Arrays.copyOfRange(array, offset, offset + length), 0, length);
    }
}
