# CL-062 — Prompt 重复注入清单（未改代码，待人类定夺）

## 问题

`OutputLanguageService.buildNaturalStyleBlock()` 与 `PromptRuleEngine` 均注入「括号内心独白 / 禁止动作描写」等 natural-style 规则，导致 system prompt **重复段落**。

## 重复点

| 位置 | 内容 |
|------|------|
| `ConversationService.buildSystemPromptForUser` (~913) | 经 Prompt 组装链追加 natural-style |
| `OutputLanguageService.buildNaturalStyleBlock` | 按语言输出 inner-thought / no-action-parentheses 规则 |
| `PromptRuleEngine`（lianyu-ai） | 同类规则 hook |

## 建议方案（三选一）

1. **仅保留 PromptRuleEngine**：删除 ConversationService 路径上对 `buildNaturalStyleBlock` 的显式 append。
2. **仅保留 OutputLanguageService**：从 PromptRuleEngine 移除重叠 rule id。
3. **合并为单一 `NaturalStyleRuleHook`**：由 `OutputLanguageService` 供文本，`PromptRuleEngine` 只注册一个 hook。

## 验证

改后需 A/B 对比同一角色 3 轮对话输出，确认 inner-thought 开关与语言切换行为不变。

**本轮未修改 prompt 内容**（`autoFixable=no`）。
