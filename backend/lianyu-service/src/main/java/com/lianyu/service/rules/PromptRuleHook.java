package com.lianyu.service.rules;

public interface PromptRuleHook {
    PromptRuleSlot slot();

    default int order() {
        return 100;
    }

    String render(PromptRuleContext context);
}
