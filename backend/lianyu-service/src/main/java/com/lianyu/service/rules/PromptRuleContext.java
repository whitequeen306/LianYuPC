package com.lianyu.service.rules;

import com.lianyu.service.character.CharacterChatBehavior;

public record PromptRuleContext(
        String outputLanguage,
        String persona,
        CharacterChatBehavior behavior,
        String characterName,
        Integer maxPieces,
        String otherCharactersLine,
        String mentionContext,
        Boolean showInnerThoughts
) {
    public static PromptRuleContext forReply(String outputLanguage, String persona,
                                             CharacterChatBehavior behavior, boolean showInnerThoughts) {
        return new PromptRuleContext(outputLanguage, persona, behavior, null, null, null, null, showInnerThoughts);
    }

    public static PromptRuleContext forOutputLanguage(String outputLanguage) {
        return new PromptRuleContext(outputLanguage, null, null, null, null, null, null, null);
    }

    public static PromptRuleContext forGroupChat(String outputLanguage,
                                                 String characterName,
                                                 int maxPieces,
                                                 String otherCharactersLine,
                                                 String mentionContext,
                                                 boolean showInnerThoughts) {
        return new PromptRuleContext(
                outputLanguage,
                null,
                null,
                characterName,
                maxPieces,
                otherCharactersLine,
                mentionContext,
                showInnerThoughts
        );
    }

    public boolean showInnerThoughtsEnabled() {
        return showInnerThoughts == null || showInnerThoughts;
    }
}
