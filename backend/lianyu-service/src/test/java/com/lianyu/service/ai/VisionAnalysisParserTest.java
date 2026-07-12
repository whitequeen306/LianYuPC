package com.lianyu.service.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class VisionAnalysisParserTest {

    private final VisionAnalysisParser parser = new VisionAnalysisParser(new ObjectMapper());

    @Test
    void parsesPlainJson() {
        String raw = "{"
                + "\"subIntent\":\"求识图\"," 
                + "\"confidence\":\"high\"," 
                + "\"imageDescription\":\"一只橘猫趴在窗台上\"}";

        VisionAnalysisResult r = parser.parse(raw);

        assertEquals("求识图", r.subIntent());
        assertEquals("high", r.confidence());
        assertEquals("一只橘猫趴在窗台上", r.imageDescription());
    }

    @Test
    void parsesJsonWrappedByJsonTag() {
        String raw = "<json>{\"subIntent\":\"分享日常\",\"confidence\":\"medium\",\"imageDescription\":\"室内自拍\"}</json>";

        VisionAnalysisResult r = parser.parse(raw);

        assertEquals("分享日常", r.subIntent());
        assertEquals("medium", r.confidence());
        assertEquals("室内自拍", r.imageDescription());
    }

    @Test
    void lowConfidenceDetected() {
        assertTrue(VisionAnalysisParser.isLowConfidence("low"));
        assertTrue(VisionAnalysisParser.isLowConfidence("看不清"));
    }

    @Test
    void invalidJsonThrows() {
        assertThrows(IllegalArgumentException.class, () -> parser.parse("not-json"));
    }
}
