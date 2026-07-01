# 可复用能力（Reusable Capabilities）

从 LianYu-PC 抽取、可迁移到其它项目的**能力规范**（Capability Spec），不是变更日志。

| 文档 | 适用场景 | 类型 |
|------|----------|------|
| [electron-client-hardening-zh.md](./electron-client-hardening-zh.md) | Electron 桌面客户端安全加固（红/蓝队） | 客户端 · 安全 |
| [prompt-rule-hooks-zh.md](./prompt-rule-hooks-zh.md) | LLM 对话 system prompt 规则插件化（槽位 + Hook） | 后端 · Spring AI |

## 给智能体 / 协作者怎么用

1. 先读本目录 **README**，再读目标能力文档的 **§0 给智能体怎么用**。
2. **只复用机制与顺序**，禁止照搬 LianYu 的业务文案、密钥、域名、HTTP 头前缀。
3. 参考实现路径在各自文档 **§迁移** 表中；拷贝时按目标项目包结构改名。

## 与本仓库其它文档的关系

| 路径 | 内容 |
|------|------|
| `docs/reusable-capabilities/` | **跨项目可复用**能力规范（本目录） |
| `docs/superpowers/` | LianYu 产品功能设计与计划 |
| `CLAUDE.md` | 本仓库协作约定与技术栈 |

CodeCortexLoop 等本地审计产物（`.cortexloop/`、`docs/cortexloop/`）**不入库**，见根目录 `.gitignore`。
