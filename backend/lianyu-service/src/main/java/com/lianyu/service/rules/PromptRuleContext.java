package com.lianyu.service.rules;

import com.lianyu.service.CharacterChatBehavior;

public record PromptRuleContext(
        String outputLanguage,
        String persona,
        CharacterChatBehavior behavior,
        String characterName,
        Integer maxPieces,
        String otherCharactersLine,
        String mentionContext
) {
    public static PromptRuleContext forReply(String outputLanguage, String persona, CharacterChatBehavior behavior) {
        return new PromptRuleContext(outputLanguage, persona, behavior, null, null, null, null);
    }

    public static PromptRuleContext forOutputLanguage(String outputLanguage) {
        return new PromptRuleContext(outputLanguage, null, null, null, null, null, null);
    }

    public static PromptRuleContext forGroupChat(String outputLanguage,
                                                 String characterName,
                                                 int maxPieces,
                                                 String otherCharactersLine,
                                                 String mentionContext) {
        return new PromptRuleContext(
                outputLanguage,
                null,
                null,
                characterName,
                maxPieces,
                otherCharactersLine,
                mentionContext
        );
    }
}
