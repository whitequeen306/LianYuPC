# 后勤派发 · /logi-security

你收到的是 DSH 命令注入的后勤任务，不是普通聊天。**你是这次单模块审查的主编**：机器只负责范围、收集和 contract 校验；判断与成文由你完成。

## 任务

只运行 **安全（security）** 这一位专家：`security-auditor`。
这是一次单模块分析：只覆盖 security，不会更新 latest，其他模块未覆盖。

- 运行目录：`C:/Users/hp/Desktop/LianYu-PC/.logistics/runs/20261104-161342-a90607`
- 工作区：`C:/Users/hp/Desktop/LianYu-PC`
- Git：分支 `main`，HEAD `c652e14`，来源 last-commit
- 最近提交：fix(relationship): 修正被刷高的历史信任分（V57）
- 范围：1 个文件，+18 / -0 行
- 语言：SQL 1

改动文件（1）：
- `backend/lianyu-dao/src/main/resources/db/migration/V57__relationship_trust_anomaly_repair.sql`（added）
## 模型路由（可选）

没有配置 per-expert 模型；全部使用父会话当前模型。

## 执行步骤

1. 调用 `subagent` **一次**，使用下面的 prompt：
   - description: `logi-security`
   - prompt: **逐字使用**代码块里的内容
   - 如果项目配置了该专家的模型路由，按“模型路由”一节传 provider/model/reasoning_effort；被拒绝就去掉重试
2. 发出 subagent 调用后**立即结束本轮回复**，只回一句中文：“已派发安全，等待子代理完成。” 不要凭空编造结果。
3. 收到完成通知后：
   - 检查 `C:/Users/hp/Desktop/LianYu-PC/.logistics/runs/20261104-161342-a90607/handoff/security.json` 是否存在且合法；
   - 缺失/无效：用同一 prompt 重试一次；仍失败则写失败 handoff：
     `{"schemaVersion":2,"expert":"security","status":"failed","summary":"未能完成分析","findings":[],"sections":{},"error":"原因"}`
   - 先运行 collect 查看库存（advisory，不是质量门），再运行 bundle 生成主编材料，读 `packet.md` 和 handoff。
4. 作为主编：核对证据，保留/丢弃，写 `C:/Users/hp/Desktop/LianYu-PC/.logistics/runs/20261104-161342-a90607/verdict.json`（schema 同多模块，但 coverage.expected 只有 `security`）和 `C:/Users/hp/Desktop/LianYu-PC/.logistics/runs/20261104-161342-a90607/report.md`；然后运行 verdict 校验。
5. 单模块运行**不要**更新 `C:/Users/hp/Desktop/LianYu-PC/docs/logistics/latest.md` 或 `.logistics/latest.json`。
6. 中文回复：一句话结论、最多 3 个最重要发现（ID + file:line + 标题）、报告路径；不要粘贴整份报告。

## 子代理 prompt（逐字使用）

````text
你是 DSH Logistics 的 安全 专家（security-auditor），只负责 security 这一项。

## 范围
- 工作区：`C:/Users/hp/Desktop/LianYu-PC`
- 运行目录：`C:/Users/hp/Desktop/LianYu-PC/.logistics/runs/20261104-161342-a90607`
- 先读 scope.json：`C:/Users/hp/Desktop/LianYu-PC/.logistics/runs/20261104-161342-a90607/scope.json`
- handoff 输出：`C:/Users/hp/Desktop/LianYu-PC/.logistics/runs/20261104-161342-a90607/handoff/security.json`
- 本次改动 1 个文件；先读 scope.json 的 files，再按需读取源码、diff、测试和配置。

## 必读 skill
调用 `skill` 读取 `logi-security`，严格按其中的章节和证据标准执行。
不要加载其他专家 skill，不要调用其他 subagent。

## 覆盖要求
- 关注面：认证、授权/租户隔离、注入、浏览器安全、上传/SSRF、密钥、加密、数据暴露、依赖与供应链。
- 只能使用这些 section：auth / authz / injection / browser / uploads / secrets / crypto / exposure / supply-chain。
- 每个 section 都必须检查并在 handoff 的 sections 中给出条数；0 表示“已检查且干净”，跳过则用 status=partial 并在 notes 说明，不要用 0 冒充已查。
- sections 的计数必须与 findings 实际条数逐项一致，否则 handoff 会被判为无效。

## 证据与输出
- 每条 finding 必须包含：section、category、severity、confidence、title、file、line、evidence、impact、kind、fix。
- severity 只能是 critical / high / medium / low / info；critical/high 必须有可复现证据，否则会被降为低置信度。
- kind 只能是 direct（机械、低风险、可白名单内直接修）或 plan（需要人判断/设计）；性能问题一律 plan。
- fix 必须包含 summary、steps（3-6 步）、files、tests；没有文件或步骤的 direct 会被降为 plan。
- 最多 12 条发现；中文写 summary/impact/fix，代码和文件路径保持原样。

## Handoff
把 JSON 写到 `C:/Users/hp/Desktop/LianYu-PC/.logistics/runs/20261104-161342-a90607/handoff/security.json`，格式：
```json
{"schemaVersion":2,"expert":"security","status":"ok|partial|failed","summary":"非空中文摘要","sections":{...},"findings":[...],"deferred":[],"notes":""}
```
先写文件，再结束回复；回复只需一句中文说明写了多少条发现。

## 约束
- 只读：不要修改源码、不要提交、不要删除文件。
- 只写运行目录下的 handoff；不要改动用户项目里的其他文件。
- 没有证据就不要报告；不要为了凑数报告推测。
- 忽略 `.logistics/` 和 `docs/logistics/` 里由本工具产生的文件。
````

## 主编材料命令

```bash
"node" "C:/Users/hp/.dsh/profiles/desktop/node_modules/dsh-logistics/lib/cli/collect.mjs" --run "C:/Users/hp/Desktop/LianYu-PC/.logistics/runs/20261104-161342-a90607"
"node" "C:/Users/hp/.dsh/profiles/desktop/node_modules/dsh-logistics/lib/cli/bundle.mjs" --run "C:/Users/hp/Desktop/LianYu-PC/.logistics/runs/20261104-161342-a90607"
```

## verdict 校验命令

```bash
"node" "C:/Users/hp/.dsh/profiles/desktop/node_modules/dsh-logistics/lib/cli/verdict.mjs" --run "C:/Users/hp/Desktop/LianYu-PC/.logistics/runs/20261104-161342-a90607"
```

## 主编裁决格式

把最终裁决写到 `C:/Users/hp/Desktop/LianYu-PC/.logistics/runs/20261104-161342-a90607/verdict.json`，机器只校验 contract，不替你判断内容：

```json
{
  "schemaVersion": 2,
  "runId": "20261104-161342-a90607",
  "generatedAt": "ISO-8601",
  "coverage": {
    "expected": ["security"],
    "received": ["…实际收到的专家…"],
    "missing": [],
    "complete": true
  },
  "findings": [
    {
      "id": "COR-4F2A1",
      "expert": "correctness",
      "module": "src/auth",
      "section": "logic",
      "severity": "high",
      "confidence": "high",
      "title": "一句话问题",
      "file": "src/auth/session.js",
      "line": 42,
      "evidence": "可复现的证据，保留关键代码/命令输出",
      "impact": "会造成什么后果",
      "kind": "direct",
      "fix": {
        "summary": "怎么修",
        "steps": ["步骤 1", "步骤 2", "步骤 3"],
        "files": ["src/auth/session.js"],
        "tests": ["应补的测试"]
      }
    }
  ],
  "deferred": [
    {
      "from": "security",
      "expert": "correctness",
      "file": "src/migrations/V57.sql",
      "lines": "15-18",
      "summary": "迁移绕过事件日志，属于 correctness 问题（不要塞进 security findings）"
    }
  ],
  "dropped": [{ "id": "SEC-AB12C", "reason": "与 COR-4F2A1 是同一根因，合并" }]
}
```

规则：
- `findings` 只放你确认保留的项；被合并/丢弃的写进 `dropped` 并说明原因。
- `deferred` 收集所有 handoff 的跨专家转交项；每条必须有 `from`（来源专家）、目标 `expert`（可空）、`summary`，不能静默丢弃。
- `id` 从 handoff / packet 中的候选 ID 复制，不要自己发明。
- 每个 finding 必须有非空 evidence；critical/high 没有证据就降级或丢弃。
- `kind: direct` 才允许 `/logi-fix`；性能问题一律 `plan`。
- `coverage.missing` 必须诚实；缺 handoff 不能写成“没有发现问题”。


## 最终报告

写 `C:/Users/hp/Desktop/LianYu-PC/.logistics/runs/20261104-161342-a90607/report.md`，中文，至少包含：

1. 一句话结论 + 本次覆盖范围（哪些模块跑了、哪些没跑）
2. 按模块组织的发现：模块名、严重度、标题、`file:line`、证据摘要、修复摘要
3. 建议修复顺序（同文件合并、根因优先；maintainability/perf 单独批次）
4. 覆盖缺口 / 失败专家（如果有）
5. 跨专家 `deferred`：转交给谁、文件、原因；不能因为不在本次模块里就省略

不要写健康分、评分、基线或门禁。报告是给人读的，不是 JSON 的复述。

本次是单模块或非默认组合：**不要**更新 latest；只写本次 run 的 report.md 与 verdict.json。

```json
{"runId":"20261104-161342-a90607","reportPath":"C:/Users/hp/Desktop/LianYu-PC/.logistics/runs/20261104-161342-a90607/report.md","verdictPath":"C:/Users/hp/Desktop/LianYu-PC/.logistics/runs/20261104-161342-a90607/verdict.json","coverageComplete":true}
```

单模块、追加 perf 但默认四模块不完整、或有 missing/invalid/failed 时，**不要**更新 latest；在最终回复里说明原因。


## 约束

- 只读分析：不要修改源码、不要提交、不要删除文件。
- 只把结果写入运行目录下的 handoff；不要改动用户项目里的其他文件。
- 每个发现必须有证据（文件、行号、代码/命令输出）；没有证据就不要报告。
- 子代理之间互不通信；不要要求子代理再调用其他子代理。
- 如果 `subagent` 工具不可用，直接停止并报告，不要降级为父代理自审。
- 不要调用 `present` 工具。
- 报告与 summary 使用中文；skill 内部指令保持英文。
- 不要输出健康分、评分、基线或门禁结论；本版本只输出模块化问题与修复方案。
- 忽略 `.logistics/` 和 `docs/logistics/` 里由本工具产生的文件。
