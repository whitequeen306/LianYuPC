package com.lianyu.service.voice;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * 帧对齐器的契约测试。
 *
 * <p>这些断言直接对应线上故障现象：切片长度为奇数 / 起始偏移为奇数时，
 * 客户端会把整帧样本的高低字节对调，听感是电子杂音。所以「发出的每一帧都从样本边界
 * 开始且长度为偶数字节」是必须钉死的不变量，而不是实现细节。
 */
class PcmFrameAssemblerTest {

    private static List<byte[]> collect(PcmFrameAssembler assembler, byte[]... chunks) {
        List<byte[]> frames = new ArrayList<>();
        PcmFrameAssembler.FrameConsumer sink = PcmFrameAssembler.emitCopying(
                (array, offset, length) -> frames.add(Arrays.copyOfRange(array, offset, offset + length)));
        for (byte[] chunk : chunks) {
            assembler.push(chunk, sink);
        }
        assembler.flush(sink);
        return frames;
    }

    @Test
    void everyEmittedFrameIsSampleAligned() {
        Random rnd = new Random(42);
        // 故意用奇数长度、极短切片，模拟 TCP/DataBuffer 的任意边界
        byte[][] chunks = new byte[37][];
        for (int i = 0; i < chunks.length; i++) {
            chunks[i] = new byte[1 + rnd.nextInt(1000) | 0];
        }
        chunks[0] = new byte[999];   // 奇数
        chunks[1] = new byte[1];     // 极短

        List<byte[]> frames = collect(new PcmFrameAssembler(), chunks);
        assertTrue(!frames.isEmpty(), "should emit at least one frame");
        for (byte[] frame : frames) {
            assertEquals(0, frame.length % PcmFrameAssembler.BYTES_PER_SAMPLE,
                    "frame length must be a whole number of samples");
            assertTrue(frame.length <= PcmFrameAssembler.FRAME_BYTES, "frame must not exceed FRAME_BYTES");
        }
        // 除最后一帧外都应是满帧，保证帧数可预测
        for (int i = 0; i < frames.size() - 1; i++) {
            assertEquals(PcmFrameAssembler.FRAME_BYTES, frames.get(i).length);
        }
    }

    @Test
    void bytesAreNeverReorderedOrDuplicated() {
        byte[] input = new byte[24_001];  // 奇数长度，故意留半个样本
        new Random(7).nextBytes(input);
        List<byte[]> frames = collect(new PcmFrameAssembler(), input);

        ByteArrayOutputStream joined = new ByteArrayOutputStream();
        frames.forEach(f -> joined.write(f, 0, f.length));
        byte[] actual = joined.toByteArray();

        // 末尾半个样本被丢弃，其余字节必须原样、同序
        assertEquals(input.length - 1, actual.length);
        assertArrayEquals(Arrays.copyOf(input, input.length - 1), actual);
    }

    @Test
    void flushEmitsTailAndDropsHalfSample() {
        PcmFrameAssembler assembler = new PcmFrameAssembler();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PcmFrameAssembler.FrameConsumer sink = (array, offset, length) ->
                out.write(array, offset, length);

        assembler.push(new byte[1001], sink);
        assertEquals(0, out.size(), "不满一帧不该发");
        assertEquals(1001, assembler.pendingBytes());

        assembler.flush(sink);
        assertEquals(1000, out.size(), "flush 必须丢掉最后那个半样本");
        assertEquals(0, assembler.pendingBytes(), "flush 之后不得残留，否则会串到下一句");
    }

    @Test
    void flushOnEmptyBufferEmitsNothing() {
        PcmFrameAssembler assembler = new PcmFrameAssembler();
        List<byte[]> frames = new ArrayList<>();
        assembler.flush((a, o, l) -> frames.add(Arrays.copyOfRange(a, o, o + l)));
        assertTrue(frames.isEmpty());
    }

    @Test
    void exactMultipleProducesNoTail() {
        PcmFrameAssembler assembler = new PcmFrameAssembler();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PcmFrameAssembler.FrameConsumer sink = (array, offset, length) -> out.write(array, offset, length);

        assembler.push(new byte[PcmFrameAssembler.FRAME_BYTES * 2], sink);
        assertEquals(PcmFrameAssembler.FRAME_BYTES * 2, out.size());
        assembler.flush(sink);
        assertEquals(PcmFrameAssembler.FRAME_BYTES * 2, out.size(), "整帧之后 flush 不该再发东西");
    }

    @Test
    void silenceIsSampleAligned() {
        byte[] s280 = PcmFrameAssembler.silence(280);
        assertEquals(13_440, s280.length);                 // 24k × 2 × 0.28s
        assertEquals(0, s280.length % 2);
        assertArrayEquals(new byte[13_440], s280);

        assertEquals(0, PcmFrameAssembler.silence(0).length);
        assertEquals(0, PcmFrameAssembler.silence(-5).length);
        // 1ms 量化成 48 字节，仍是偶数
        assertEquals(48, PcmFrameAssembler.silence(1).length);
    }

    @Test
    void frameSizeMatchesClientExpectation() {
        // 前端 createPcmPlayback 默认 24000；Python STREAM_SAMPLE_RATE 也是 24000。
        // 三者任一改动都会变调，所以在这里钉住。
        assertEquals(24000, PcmFrameAssembler.SAMPLE_RATE);
        assertEquals(4800, PcmFrameAssembler.FRAME_BYTES);
    }
}
