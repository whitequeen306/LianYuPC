package com.lianyu.service.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MultimodalOutputParserTest {

    private final MultimodalOutputParser parser = new MultimodalOutputParser(new ObjectMapper());

    @Test
    void parsesJsonTagThenReply() {
        String raw = "<json>{\"sub_intent\":\"分享日常\",\"confidence\":\"high\",\"image_description\":\"咖啡馆下午茶\"}</json>\n"
                + "哇，这家咖啡馆看起来好温馨！你今天去那里坐了一会儿吗？";

        MultimodalOutputParser.Result r = parser.parse(raw);

        assertEquals("分享日常", r.subIntent());
        assertEquals("high", r.confidence());
        assertEquals("咖啡馆下午茶", r.imageDescription());
        assertEquals("哇，这家咖啡馆看起来好温馨！你今天去那里坐了一会儿吗？", r.reply());
    }

    @Test
    void parsesMarkdownFencedJson() {
        String raw = "```json\n{\"sub_intent\":\"求识图\",\"confidence\":\"medium\",\"image_description\":\"一只橘猫\"}\n```\n"
                + "这好像是一只橘猫诶，毛色很漂亮。";

        MultimodalOutputParser.Result r = parser.parse(raw);

        assertEquals("求识图", r.subIntent());
        assertEquals("medium", r.confidence());
        assertEquals("一只橘猫", r.imageDescription());
        assertEquals("这好像是一只橘猫诶，毛色很漂亮。", r.reply());
    }

    @Test
    void parsesBareBraceJsonFallback() {
        String raw = "{\"sub_intent\":\"炫耀\",\"confidence\":\"high\",\"image_description\":\"新发型\"}\n嘿嘿，新发型好看吧？";

        MultimodalOutputParser.Result r = parser.parse(raw);

        assertEquals("炫耀", r.subIntent());
        assertEquals("high", r.confidence());
        assertEquals("新发型", r.imageDescription());
        assertEquals("嘿嘿，新发型好看吧？", r.reply());
    }

    @Test
    void noJsonReturnsWholeAsReply() {
        String raw = "这张图我没太看明白，能再说说是怎么回事吗？";

        MultimodalOutputParser.Result r = parser.parse(raw);

        assertEquals(raw, r.reply());
        assertNull(r.subIntent());
        assertNull(r.confidence());
    }

    @Test
    void emptyInputReturnsEmptyReply() {
        MultimodalOutputParser.Result r = parser.parse("");
        assertEquals("", r.reply());
        assertNull(r.subIntent());
    }

    @Test
    void nullInputReturnsEmptyReply() {
        MultimodalOutputParser.Result r = parser.parse(null);
        assertEquals("", r.reply());
    }

    @Test
    void malformedJsonReturnsWholeAsReply() {
        String raw = "<json>{not valid json}</json>\n回复内容";
        MultimodalOutputParser.Result r = parser.parse(raw);
        assertEquals(raw, r.reply());
        assertNull(r.subIntent());
    }

    @Test
    void lowConfidenceDetected() {
        assertTrue(MultimodalOutputParser.isLowConfidence("low"));
        assertTrue(MultimodalOutputParser.isLowConfidence("LOW"));
        assertTrue(MultimodalOutputParser.isLowConfidence("看不清"));
        assertTrue(MultimodalOutputParser.isLowConfidence("图片模糊"));
    }

    @Test
    void highConfidenceNotLow() {
        assertFalse(MultimodalOutputParser.isLowConfidence("high"));
        assertFalse(MultimodalOutputParser.isLowConfidence(null));
    }

    @Test
    void replyTrimmedOfLeadingNewlines() {
        String raw = "<json>{\"sub_intent\":\"分享日常\",\"confidence\":\"high\",\"image_description\":\"x\"}</json>\n\n\n实际回复";
        MultimodalOutputParser.Result r = parser.parse(raw);
        assertEquals("实际回复", r.reply());
    }
}
