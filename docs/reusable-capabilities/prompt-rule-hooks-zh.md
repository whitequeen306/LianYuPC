# Prompt 规则 Hook 插件化能力手册（Spring · 可复用）

> **文档类型：** 能力规范（Capability Spec），不是某次迭代的变更日志。  
> **用法：** 给其它项目的智能体/工程师作**参考**——在拼 system prompt 时插件化「回复规则 / 语言规则 / 场景规则」。  
> **边界：** 本机制管 **prompt 文案组装**，不是 Spring AI `Advisor`（调用链拦截）；二者可并存。

---

## 0. 给智能体怎么用

```text
读 docs/reusable-capabilities/prompt-rule-hooks-zh.md。
为目标 Spring 项目实现 §2 四个核心类型 + §3 集成点；
按 §4 槽位表添加业务 Hook（Java @Component）；
用 §5 验收清单做单元测试。
只复用接口与引擎，禁止照搬 LianYu 的拟人聊天/群聊/多语言具体文案。
```

**适配时必改：** 槽位枚举、各 Hook 的 `render()` 文案、与 `CharacterPromptBuilder` 等价的 prompt 组装类名与包路径。

**命名建议：** 对外可称 **PromptRuleContributor**；LianYu 源码中仍叫 `PromptRuleHook`（语义是「贡献一段规则文本」，不是 Git/中间件钩子）。

---

## 1. 能力总览

| ID | 能力 | 解决什么问题 | 核心类型 | 优先级 |
|----|------|--------------|----------|--------|
| R1 | 槽位化规则 | 规则与角色设定耦在一坨字符串里 | `PromptRuleSlot` | 必做 |
| R2 | 插件化贡献 | 加规则要改巨型 Builder | `PromptRuleHook` | 必做 |
| R3 | 上下文传参 | 规则需语言/人设/群聊参数 | `PromptRuleContext` | 必做 |
| R4 | 收集与拼接 | 多 Hook 排序、过滤、合并 | `PromptRuleEngine` | 必做 |
| R5 | 与 Spring 集成 | 自动发现所有 Hook | `@Component` + 构造器注入 `List<PromptRuleHook>` | 必做 |
| R6 | 与 LLM 调用边界 | 避免误用 Advisor | 规则进 **SystemMessage**，调 `ChatModel`/`ChatClient` | 认知 |

---

## 2. 核心机制（最小可复用内核）

### 2.1 接口：PromptRuleHook

```java
public interface PromptRuleHook {
    PromptRuleSlot slot();

    default int order() {
        return 100;
    }

    String render(PromptRuleContext context);
}
```

| 方法 | 含义 |
|------|------|
| `slot()` | 本 Hook 属于哪个槽位（分类） |
| `order()` | 同槽位多 Hook 时的拼接顺序，越小越靠前 |
| `render()` | 根据上下文生成 **一段 prompt 文本**；返回 blank 则跳过 |

### 2.2 槽位：PromptRuleSlot

```java
public enum PromptRuleSlot {
    REPLY_BEHAVIOR,      // 怎么回复：长度、语气、禁止项
    OUTPUT_LANGUAGE,     // 输出语言强制
    GROUP_CHAT,          // 群聊专用
    CHARACTER_GENERATION // 角色生成 JSON 时的质量标准
}
```

迁移时可增删枚举值；**不要**把业务字段塞进 enum，字段放 `PromptRuleContext`。

### 2.3 上下文：PromptRuleContext

推荐 `record` + 静态工厂，避免 Builder 膨胀：

```java
public record PromptRuleContext(
        String outputLanguage,
        String persona,
        CharacterChatBehavior behavior,  // 目标项目可换成自己的类型或 Map
        String characterName,
        Integer maxPieces,
        String otherCharactersLine,
        String mentionContext,
        Boolean showInnerThoughts
) {
    public static PromptRuleContext forReply(String lang, String persona,
                                             Object behavior, boolean showInner) { ... }
    public static PromptRuleContext forOutputLanguage(String lang) { ... }
    public static PromptRuleContext forGroupChat(...) { ... }
}
```

### 2.4 引擎：PromptRuleEngine

```java
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
```

**特性：** Spring 自动注入所有 `PromptRuleHook` 实现；新增规则 = 新增 `@Component`，**不改引擎**。

---

## 3. 集成点（在完整 prompt 中的位置）

典型对话 system prompt 组装顺序（LianYu 参考）：

```text
① 角色 promptTemplate + persona
② 长期记忆块（Memory / RAG 预注入）
③ Tool 使用说明（若启用 Spring AI @Tool）
④ PromptRuleEngine → REPLY_BEHAVIOR
⑤ PromptRuleEngine → OUTPUT_LANGUAGE
⑥ 情绪 / 状态块（业务可选）
⑦ 防 prompt 注入说明（UserInputSanitizer 等）
⑧ Conversation 层追加：当前时间、城市、场景指令…
```

**调用示例（概念）：**

```java
String replyRules = promptRuleEngine.render(
        PromptRuleSlot.REPLY_BEHAVIOR,
        PromptRuleContext.forReply(outputLanguage, persona, behavior, showInnerThoughts));
if (!replyRules.isBlank()) {
    prompt += "\n\n" + replyRules;
}
```

群聊在 base prompt 之后追加：

```java
promptRuleEngine.render(PromptRuleSlot.GROUP_CHAT, PromptRuleContext.forGroupChat(...));
```

角色生成（非对话）：

```java
sysPrompt += promptRuleEngine.render(PromptRuleSlot.CHARACTER_GENERATION, ctx);
```

---

## 4. LianYu 参考实现（Hook 清单）

| Hook 类 | 槽位 | 职责摘要 |
|---------|------|----------|
| `ReplyBehaviorRuleHook` | `REPLY_BEHAVIOR` | 拟人回复、拆条、内心独白、禁 AI 腔；`lianyu.chat.humanize.enabled` |
| `OutputLanguageRuleHook` | `OUTPUT_LANGUAGE` | 强制中/英/日/繁输出，覆盖历史语言漂移 |
| `GroupChatRuleHook` | `GROUP_CHAT` | 群聊发言顺序、@ 上下文、多人场景 |
| `CharacterGenerationRuleHook` | `CHARACTER_GENERATION` | AI 生成角色 JSON 的质量约束 |

**参考路径（LianYu-PC）：**

| 职责 | 路径 |
|------|------|
| 接口 | `backend/lianyu-service/.../rules/PromptRuleHook.java` |
| 引擎 | `backend/lianyu-service/.../rules/PromptRuleEngine.java` |
| 槽位 | `backend/lianyu-service/.../rules/PromptRuleSlot.java` |
| 上下文 | `backend/lianyu-service/.../rules/PromptRuleContext.java` |
| Hook 实现 | `backend/lianyu-service/.../rules/hooks/*.java` |
| 组装入口 | `backend/lianyu-service/.../ai/CharacterPromptBuilder.java` |
| 对话调用链 | `backend/lianyu-service/.../conversation/ConversationService.java` |
| LLM 调用 | `backend/lianyu-service/.../ai/AiChatService.java`（`ChatModel` + `Prompt`，无 Advisor） |

---

## 5. 验收清单（迁移后必测）

```text
1. 新增一个测试 Hook（slot=REPLY_BEHAVIOR，order=50）→ render 固定字符串
2. PromptRuleEngine.render 应包含该字符串，且位于同 slot 其它 Hook 按 order 拼接
3. render 返回 null/blank 的 Hook 不出现在结果中
4. 两个 Hook 同 slot → 中间用双换行连接
5. CharacterPromptBuilder（或等价类）集成后，最终 SystemMessage 含规则段且顺序符合 §3
6. 单测不启动 LLM：只断言 prompt 字符串
```

**可选增强（LianYu 尚未做，复用库可加）：**

| 增强 | 说明 |
|------|------|
| `supports(PromptRuleContext)` | 条件跳过 Hook |
| YAML/DB 规则模板 | 运营可改文案，Java 只负责占位符 |
| `prompt-rule-spring-boot-starter` | 单独 jar：`Engine` + 接口 + 自动配置 |

---

## 6. 与 Spring AI Advisor 的关系（勿混淆）

| | PromptRuleHook（本能力） | Spring AI Advisor |
|--|--------------------------|-------------------|
| 层级 | 拼 **system prompt 文本** | 包 **ChatModel 调用** |
| 时机 | 调模型**之前**，一次性字符串 | 每次 `call/stream` 链式执行 |
| 典型用途 | 回复风格、语言、群聊规则 | Memory、RAG、SafeGuard、观测 |
| LianYu 现状 | ✅ 使用 | ❌ 未使用 |

**推荐组合：** 保留 Hook 管「写什么规则」；若未来引入 `ChatClient`，可用少量 Advisor 管日志/安全/重试，**不必**把 Hook 改成 Advisor。

---

## 7. 迁移到其它项目（能力裁剪）

| 场景 | 建议 |
|------|------|
| 只有单聊 + 固定中文 | `REPLY_BEHAVIOR` + 一个 Hook 即可 |
| 多语言产品 | + `OUTPUT_LANGUAGE` Hook |
| 群聊 | + `GROUP_CHAT` Hook + 群聊 prompt 追加方法 |
| 角色 AI 生成 | + `CHARACTER_GENERATION` Hook |
| 抽独立 starter | 复制 §2 四文件 + 测试；业务 Hook 留宿主项目 |

**不要复用到其它项目的（LianYu 领域资产）：**

- `ReplyBehaviorRuleHook` 内拟人/冷回复/内心独白等具体中文案
- `CharacterPromptBuilder` 整类（强绑定角色、记忆、情绪）
- Milvus 记忆、Tool 注册表（属另一能力层）

---

## 8. 能力边界（写进方案避免误解）

| 误区 | 事实 |
|------|------|
| Hook = 中间件拦截 | 只是 **返回 prompt 片段**，不包装 HTTP/ChatModel |
| Hook = Spring AI Advisor | 层级不同，见 §6 |
| 叫 Hook 就必须 event 回调 | 更贴切名字是 **Contributor / FragmentProvider** |
| 规则进 Hook 后不能测 | `render()` 应纯函数化，易单测 |
| 外绑 skills 文件 | LianYu **无** skills 目录；规则在 Java Hook 内 |

---

*Prompt 规则 Hook 插件化能力手册 · 供跨项目复用 · 参考实现 LianYu-PC*
