package com.lianyu.service.graph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class ImageMessageHistoryTextTest {

    @Test
    void placeholder_includesDescriptionWhenPresent() {
        assertEquals("（用户发了一张图片）", ImageMessageHistoryText.placeholder(null));
        assertEquals("（用户发了一张图片（橘猫））", ImageMessageHistoryText.placeholder("橘猫"));
    }

    @Test
    void forHistory_neverNeedsImageUrl() {
        assertEquals("（用户发了一张图片（室内自拍））",
                ImageMessageHistoryText.forHistory("（用户发了一张图片（室内自拍））", true));
        assertEquals("（用户发了一张图片）",
                ImageMessageHistoryText.forHistory("（用户发送了一张图片）", true));
    }

    @Test
    void forPersist_combinesCaptionAndDescription() {
        assertEquals("（用户发了一张图片（sunset））",
                ImageMessageHistoryText.forPersist("", "sunset"));
        assertEquals("看这张图\n（用户发了一张图片（猫））",
                ImageMessageHistoryText.forPersist("看这张图", "猫"));
    }

    @Test
    void forUserVisible_keepsCaptionOnly() {
        assertEquals("（用户发送了一张图片）", ImageMessageHistoryText.forUserVisible(""));
        assertEquals("你认识她吗", ImageMessageHistoryText.forUserVisible("你认识她吗"));
        assertEquals("你认识她吗",
                ImageMessageHistoryText.forUserVisible(
                        "你认识她吗\n（用户发了一张图片（银发蓝眼角色））"));
    }

    @Test
    void forDisplay_stripsInternalPlaceholder() {
        assertEquals("你认识她吗",
                ImageMessageHistoryText.forDisplay(
                        "你认识她吗\n（用户发了一张图片（一位银发蓝眼的动漫风格女性角色））"));
        assertEquals("", ImageMessageHistoryText.forDisplay("（用户发了一张图片（橘猫））"));
        assertEquals("", ImageMessageHistoryText.forDisplay("（用户发送了一张图片）"));
    }
}
