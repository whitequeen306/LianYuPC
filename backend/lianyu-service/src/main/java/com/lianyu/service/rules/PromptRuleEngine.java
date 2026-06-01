package com.lianyu.service.rules;

import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

@Component
public class PromptRuleEngine {

    private final List<PromptRuleHook> hooks;

    public PromptRuleEngine(List<PromptRuleHook> hooks) {
        this.hooks = hooks == null ? List.of() : hooks;
    }

    public String render(PromptRuleSlot slot, PromptRuleContext context) {
        return hooks.stream()
                .filter(h -> h.slot() == slot)
                .sorted(Comparator.comparingInt(PromptRuleHook::order))
                .map(h -> h.render(context))
                .filter(s -> s != null && !s.isBlank())
                .reduce((a, b) -> a + "\n\n" + b)
                .orElse("");
    }
}
