package com.lianyu.qqbridge.bridge;

import com.lianyu.qqbridge.napcat.OneBotModels;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 纯单元测试：OneBot 消息段数组 → 纯文本。覆盖 text / at(self|other|all) / 富媒体忽略 / trim / null 兜底。
 */
class MessageSegmentExtractorTest {

    private static OneBotModels.Segment text(String t) {
        return new OneBotModels.Segment("text", Map.of("text", t));
    }

    private static OneBotModels.Segment at(String qq) {
        return new OneBotModels.Segment("at", Map.of("qq", qq));
    }

    @Test
    void nullOrEmpty_returnsEmpty() {
        assertThat(MessageSegmentExtractor.toPlainText(null, 0L)).isEmpty();
        assertThat(MessageSegmentExtractor.toPlainText(List.of(), 0L)).isEmpty();
    }

    @Test
    void singleText_returnsText() {
        assertThat(MessageSegmentExtractor.toPlainText(List.of(text("你好")), 0L)).isEqualTo("你好");
    }

    @Test
    void multipleTexts_concatenated() {
        assertThat(MessageSegmentExtractor.toPlainText(List.of(text("你"), text("好"), text("呀")), 0L))
                .isEqualTo("你好呀");
    }

    @Test
    void atSelf_stripped() {
        assertThat(MessageSegmentExtractor.toPlainText(List.of(at("10001"), text("你好")), 10001L))
                .isEqualTo("你好");
    }

    @Test
    void atOther_keptAsAtQq() {
        assertThat(MessageSegmentExtractor.toPlainText(List.of(at("99999"), text("嗨")), 10001L))
                .isEqualTo("@99999 嗨");
    }

    @Test
    void atAll_becomesAtAll() {
        assertThat(MessageSegmentExtractor.toPlainText(List.of(at("all"), text("通知")), 10001L))
                .isEqualTo("@全体 通知");
    }

    @Test
    void selfIdZero_doesNotStripAtSelf() {
        // selfId=0 → 不剔除 @自己，按 @他人 处理
        assertThat(MessageSegmentExtractor.toPlainText(List.of(at("10001"), text("嗨")), 0L))
                .isEqualTo("@10001 嗨");
    }

    @Test
    void imageAndFace_ignored() {
        OneBotModels.Segment img = new OneBotModels.Segment("image", Map.of("url", "http://x"));
        OneBotModels.Segment face = new OneBotModels.Segment("face", Map.of("id", "1"));
        assertThat(MessageSegmentExtractor.toPlainText(List.of(img, face, text("看图")), 0L))
                .isEqualTo("看图");
    }

    @Test
    void mixedTextAtSelfAtOtherImage() {
        OneBotModels.Segment img = new OneBotModels.Segment("image", Map.of("url", "http://x"));
        assertThat(MessageSegmentExtractor.toPlainText(
                List.of(at("10001"), text("喂"), at("88888"), img, text("!")), 10001L))
                .isEqualTo("喂@88888 !");
    }

    @Test
    void whitespaceTrimmed() {
        assertThat(MessageSegmentExtractor.toPlainText(List.of(text("  hi  ")), 0L)).isEqualTo("hi");
    }

    @Test
    void nullDataInSegment_skipped() {
        OneBotModels.Segment noData = new OneBotModels.Segment("text", null);
        assertThat(MessageSegmentExtractor.toPlainText(List.of(noData, text("ok")), 0L)).isEqualTo("ok");
    }
}
