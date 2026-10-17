# 2026-10 大重构档案

本目录是予念（YuNian）语言级架构重构的决策与依据档案。

| 文件 | 内容 |
|---|---|
| [`refactor-plan.md`](./refactor-plan.md) | **重构总纲（唯一规划文档）**：问题诊断、as-is / to-be 架构、决策记录 D1–D7、进度台账、路线图 P1–P6、灰度 Runbook、红线、已知缺口 |
| [`audit-findings.md`](./audit-findings.md) | 四路只读审计合并台账：按「P0 先修 / 结构债（重构中消灭）/ 资产（必须保留移植）」三档分类，每条带 `path:line` 证据 |

## 背景

- 现后端：Spring Boot 3.5.5 + Spring AI 1.1.2 + Spring AI Alibaba Graph 1.1.2.2（SAA 官方已停更约 7 个月）
- 审计基线：commit `0580a07`（v0.2.367）
- 当前进度：P0 止血 / P1 agent 骨架 / P2a 主动发言族 4 条链路已落地，详见总纲 §5

## 已被本文档取代的历史文档

| 原文件 | 去向 |
|---|---|
| `docs/refactor-plan-coupling.md`（2026-09 耦合审查，19 项） | 已完成项归档至总纲 §11；未完成项并入总纲 §6；原文件已删除（可从 git 历史恢复） |
| `docs/refactor-2026-10/rewrite-options.md`（语言级重写方案对比） | 决策定稿为总纲 §4 的 D1 / D2；原文件已删除 |

## 红线（从已删除的 AGENTS.md 恢复，对新架构同样强制）

恢复副本在 `.tmp/AGENTS_recovered.md`。完整列表见总纲 §8，其中三条最关键：

1. **事务红线**：慢调用（LLM/HTTP/ASR/TTS/MinIO）不进事务 —— Python 侧对应：SQLAlchemy async session 内不做任何 await 外部服务
2. **出站 HTTP 红线**：所有出站客户端显式带超时，禁止零超时（httpx 默认 5s 但必须显式声明）—— 2026-08-07 线上 78 分钟线程 park 全站 499/504 事故的直接教训
3. **发版红线**：唯一入口 `local/ship-release.ps1`，`-BackendOnly` / `-ElectronOnly` 语义不变

## 审计来源

四路并行只读调研（未修改任何文件）：

1. 后端全模块（依赖图 / god classes / 事务 / 横切）
2. 前端 + Electron（分层 / IPC / 测试真实度）
3. AI / Agent / 记忆子系统（Graph 覆盖率 / 工具循环 / 记忆双写）——含对 `~/.m2` 中 spring-ai-openai 1.1.2 与 graph-core 1.1.2.2 的 javap 反编译核验
4. 基建 / 部署 / 发版 / 管理端
