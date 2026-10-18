# P5.5 · system prompt 组装迁移状态

> 结论：**所有能用 golden 验证的层已迁完，且与 Java 逐字节一致**。
> 剩余三块都必须接真实数据，无法再用快照比对验证。

## 一、已完成（全部逐字节验证）

| 层 | 产物 | 验证方式 |
|---|---|---|
| 文本片段基线（54 块） | `app/prompts/fragments.json` | 生成器防漂移测试：重新生成必须与仓库一致 |
| 文本片段加载 | `app/prompt_fragments.py` | 按 (文件, 行号) 定位 |
| 时间锚点 / 城市权威 | `app/prompt_assembly.py` | golden 逐字节 |
| 晚安模式（4 语种） | 同上 | 片段逐字节 |
| 自然口语风格（4 语种 × 2） | 同上 | golden 逐字节 |
| 组合顺序 | `compose_prompt` | golden 逐字节（574 字符） |
| 回复规则（16 组合） | `app/reply_rules.py` + `reply_rule_templates.json` | 模板逐字节 |
| 输出语言规则（4） | `app/rule_templates.py` + `rule_templates.json` | 模板逐字节 |
| 群聊规则（8 组合） | 同上 | 模板逐字节 |
| 角色人设（8 分支含 19/20 边界） | `app/character_prompt.py` | golden 逐字节 |
| 默认提示词骨架 | 同上 | golden 逐字节 |
| 用户称呼规则（4 语种） | `app/system_prompt.py` + `addressing_templates.json` | 模板逐字节 |
| 编排（16 组合） | `app/system_prompt.py` | golden 逐字节 |
| 记忆块格式（四段分组） | `app/memory/profile_context.py` | golden 逐字节 |
| 关系块（含 404 条标签） | `app/relationship_context.py` | golden 全量比对 |
| **整串 prompt（zh 2588 字符 / en）** | 组合上述所有层 | golden 逐字节 |

## 二、关键方法：为什么不手抄、不循环验证

1. **文本不手抄**：`scripts/extract-java-prompt-fragments.py` 从 Java 源码抽取 text block，
   按 Java 规则去缩进；`fragments.json` 再生成一次必须与仓库一致（防漂移）。
2. **不让 Python 自己算期望值**：golden 全部由 **Java 真实执行**产出
   （反射调用私有方法 / 真实 hook 渲染 / mock 固定数据）。
   否则两边同错也会通过。
3. **规则文本模板化**：`ReplyBehaviorRuleHook` 等含大量内联拼接，逐块迁移必然手抄，
   改为「Java 渲染带占位符的模板 → Python 只做替换」。
4. **穷举而非抽样**：关系块的四维标签导出 0..100 全部取值（404 条）比对。

## 三、剩余三块（都需真实数据）

| 块 | Java 侧 | 为什么快照验证不了 |
|---|---|---|
| 情绪块 | `CharacterStateService.buildEmotionBlock`（434 行） | 依赖角色状态表（当前心情/强度/状态签名/上一刻心情）；格式化与取数交织 |
| 会话摘要块 | `SessionSummaryService.formatForPrompt` | 依赖会话摘要表的写入结果 |
| 三块的真实取数 | `MemoryRetriever` / `RelationshipStateService` / 状态服务 | 缓存命中率、Milvus 降级路径、阈值都会让结果不同 |

**这三块的失败模式**：prompt 格式完全正确，但内容（记忆/关系/情绪）与 Java 不一致。
逐字节比对抓不到 —— 只能在 SHADOW 模式下用真实流量比对两边的整串 prompt。

## 四、情绪块的可行切法（下一轮起手）

`buildEmotionBlock` 的结构是「读 state → 格式化」：

```java
CharacterState state = getOrCreate(characterId, userId);   // 取数
String header = emotionHeader(lang);                        // 纯格式化
String behaviorGuide = emotionBehaviorGuide(lang, emotion, intensity);
String priorityNote = emotionPriorityNote(lang, emotion);
return header + ... + behaviorGuide + priorityNote;
```

建议：
1. 把格式化抽成 `formatEmotionBlock(state, lang)`（纯函数，无 DB），行为不变
2. golden 用构造的 `CharacterState` 覆盖：强度 4 档 × 语言 4 种 × 情绪若干 × 有无上一刻心情 × 空状态签名
3. `emotionBehaviorGuide` / `priorityNote` 若是「情绪 × 强度」表，直接由 Java 导出全表，Python 查表
4. 取数仍留 Java，Python 接收 state 字段

## 五、接线的当前状态

**Python 的 prompt 组装能力尚未接入任何线上链路**。它是被逐步验证、但未启用的能力。
接线需要：Java 在 AGENT 模式下把 character/settings/记忆块/关系块/情绪块传给
`/agent/prompt/assemble`，Python 返回 systemPrompt。这一步尚未做，也尚未设计失败回退。

## 六、验证基线（截至本轮）

- `pytest` 531 passed
- `mvn test` BUILD SUCCESS
- `vitest` 450 passed
