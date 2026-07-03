# 可复用能力（Reusable Capabilities）

跨项目可迁移的**能力规范**（Capability Spec）：描述机制、选型、验收，不含某一仓库的业务实现路径。

| 文档 | 何时需要 | 类型 |
|------|----------|------|
| [electron-client-hardening-zh.md](./electron-client-hardening-zh.md) | 发布 **Electron 桌面客户端**，且 API 含付费/私有数据 | 客户端 · 安全 |
| [prompt-rule-hooks-zh.md](./prompt-rule-hooks-zh.md) | 后端 LLM 对话的 system prompt 含多套可变「规则段落」 | 后端 · Prompt 工程 |

## 给智能体 / 工程师

1. 先确认**是否属于上表场景**；不属于则不必读对应文档。
2. 读该文档开头的 **「何时使用」**，再按能力模块实现与验收。
3. 业务文案、密钥、域名、HTTP 头前缀必须在目标项目中**重新设计**，禁止从任一参考仓库照搬。
