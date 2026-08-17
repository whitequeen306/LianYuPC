package com.lianyu.service.character;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lianyu.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

class CharacterImportSourceParserTest {

    @Test
    void rejectsBlankSource() {
        assertThrows(BusinessException.class, () -> CharacterImportSourceParser.prepare("   "));
        assertThrows(BusinessException.class, () -> CharacterImportSourceParser.prepare(null));
    }

    @Test
    void stripsControlCharacters() {
        String prepared = CharacterImportSourceParser.prepare("温柔\u0007体贴的邻家姐姐");
        assertEquals("温柔体贴的邻家姐姐", prepared);
    }

    @Test
    void keepsTailWhenSourceExceedsLimit() {
        String head = "A".repeat(4000);
        String tail = "B".repeat(CharacterImportSourceParser.MAX_CHARS);
        String prepared = CharacterImportSourceParser.prepare(head + tail);
        assertEquals(CharacterImportSourceParser.MAX_CHARS, prepared.length());
        assertTrue(prepared.chars().allMatch(ch -> ch == 'B'));
        assertFalse(prepared.contains("A"));
    }

    @Test
    void stripsSimpleHtmlTagsFromChatExport() {
        String html = "<html><body><div class=\"msg\">她：今晚月色真美</div></body></html>";
        String prepared = CharacterImportSourceParser.prepare(html);
        assertEquals("她：今晚月色真美", prepared);
        assertFalse(prepared.contains("<div"));
    }
}
