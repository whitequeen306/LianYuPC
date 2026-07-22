package com.lianyu.service.community;

import com.lianyu.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommunityContentRulesTest {

    @Test
    void rejectsEmptyPost() {
        assertThrows(BusinessException.class,
                () -> CommunityContentRules.assertPostAllowed("", List.of()));
    }

    @Test
    void allowsTextOnly() {
        assertDoesNotThrow(() -> CommunityContentRules.assertPostAllowed("你好世界", List.of()));
    }

    @Test
    void blocksForbiddenWords() {
        assertThrows(BusinessException.class,
                () -> CommunityContentRules.assertPostAllowed("涉及赌博的内容", List.of()));
        assertTrue(CommunityContentRules.passesSecondaryRules("普通日常分享"));
    }
}
