package com.lianyu.service.character;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CharacterAddressingTest {

    @Test
    void appendsHintOnce() {
        String prompt = CharacterAddressing.appendHint("你是邻家姐姐。", "笨蛋");
        assertTrue(prompt.contains("最常用的称呼是「笨蛋」"));
        assertTrue(prompt.contains("不是每句都必须这样叫"));

        String again = CharacterAddressing.appendHint(prompt, "笨蛋");
        assertEquals(prompt, again);
    }

    @Test
    void stripsQuoteMarksThatCouldBreakPrompt() {
        String prompt = CharacterAddressing.appendHint("人设", "「笨蛋」");
        assertTrue(prompt.contains("最常用的称呼是「笨蛋」"));
        assertEquals("笨蛋", CharacterAddressing.sanitize("「笨蛋」"));
    }

    @Test
    void leavesPromptUnchangedWhenAddressingBlank() {
        assertEquals("人设", CharacterAddressing.appendHint("人设", "  "));
        assertEquals("人设", CharacterAddressing.appendHint("人设", null));
    }
}
