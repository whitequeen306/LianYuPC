# 项目后勤派发 · /logi-perf

你收到的是 DSH 命令注入的**全项目审查**任务，不是普通聊天，也不是 diff 审查。**你是这次项目审查的主编**：机器只负责范围、模块划分、收集和 contract 校验；派发、证据核对、合并、定级、取舍和最终成文都由你完成。

## 任务

审查 **整个项目**，不是工作区改动，也不是最近一次提交。全项目被切成 12 个审查单元；每位专家都必须覆盖全部单元。

- 运行目录：`C:/Users/hp/Desktop/LianYu-PC/.logistics/runs/20261104-182459-9883f4`
- 工作区：`C:/Users/hp/Desktop/LianYu-PC`
- Git：分支 `main`，HEAD `c652e14`，来源 project
- 最近提交：fix(relationship): 修正被刷高的历史信任分（V57）
- 范围：全项目 1100 个文件 / 12 个审查单元 / 135978 行
- 语言：Java 450 · JavaScript 252 · Python 220 · SQL 60 · Vue 57

审查单元（12）：
- `u01` backend/lianyu-service (1/3)：120 个文件 / 12117 行
- `u02` backend/lianyu-service (2/3)：120 个文件 / 16014 行
- `u03` frontend/src (1/2)：120 个文件 / 15840 行
- `u04` scripts (1/2)：120 个文件 / 12803 行
- `u05` backend/lianyu-dao：110 个文件 / 2934 行
- `u06` backend-agent：102 个文件 / 16514 行
- `u07` frontend/src (2/2)：95 个文件 / 24648 行
- `u08` backend/lianyu-web：71 个文件 / 7840 行
- `u09` frontend/electron：68 个文件 / 14745 行
- `u10` 其他模块（(root)、.github、admin 等）：67 个文件 / 4923 行
- `u11` backend（小模块合集 59 文件）：59 个文件 / 3141 行
- `u12` 其他模块（backend/lianyu-app、backend/lianyu-common、backend 等）：48 个文件 / 4459 行
## 模型路由（可选）

没有配置 per-expert 模型；全部使用父会话当前模型。

## 专家清单

1. **性能（perf）** — `performance-analyst`，skill `logi-perf`

## 审查单元

- `u01` backend/lianyu-service (1/3)：120 个文件 / 12117 行
- `u02` backend/lianyu-service (2/3)：120 个文件 / 16014 行
- `u03` frontend/src (1/2)：120 个文件 / 15840 行
- `u04` scripts (1/2)：120 个文件 / 12803 行
- `u05` backend/lianyu-dao：110 个文件 / 2934 行
- `u06` backend-agent：102 个文件 / 16514 行
- `u07` frontend/src (2/2)：95 个文件 / 24648 行
- `u08` backend/lianyu-web：71 个文件 / 7840 行
- `u09` frontend/electron：68 个文件 / 14745 行
- `u10` 其他模块（(root)、.github、admin 等）：67 个文件 / 4923 行
- `u11` backend（小模块合集 59 文件）：59 个文件 / 3141 行
- `u12` 其他模块（backend/lianyu-app、backend/lianyu-common、backend 等）：48 个文件 / 4459 行

## 执行步骤

1. 先读 `C:/Users/hp/Desktop/LianYu-PC/.logistics/runs/20261104-182459-9883f4/scope.json`，确认 `mode: "project"`、`project.units` 和专家列表。不要改成只审查最近提交。
2. 对每位专家，调用 `subagent` 一次，prompt **逐字使用**下面的代码块；description 用 `logi-<expert>`。每条 assistant 消息最多同时发 4 个 subagent；如果选了 5 位专家，先发 4 位，收到完成通知后再发第 5 位。发出后结束本轮回复，不要编造结果。
3. 子代理必须逐个单元检查，并在 `handoff.units` 中给每个单元标记 `reviewed` / `sampled` / `skipped`。没有覆盖全部单元的 handoff 会被 collect 判为无效；不要用“文件太多”当理由静默漏掉单元。
4. 所有专家完成后，先运行 collect 查看库存；缺失/无效/partial 的专家用同一 prompt 重试一次。仍失败就写失败 handoff：
   `{"schemaVersion":2,"expert":"<expert>","status":"failed","summary":"未能完成分析","findings":[],"sections":{},"units":{},"error":"..."}`
   然后运行 bundle，读 `packet.md` 和原始 handoff。
5. 作为主编做裁决：逐条核对证据，合并跨专家重复项，同一根因保留证据最强的一条，其余写 `dropped`；critical/high 必须有可复现证据。
6. 写 `C:/Users/hp/Desktop/LianYu-PC/.logistics/runs/20261104-182459-9883f4/verdict.json` 和 `C:/Users/hp/Desktop/LianYu-PC/.logistics/runs/20261104-182459-9883f4/report.md`。报告必须包含：
   - 一句话结论 + 全项目范围（文件数、单元数、行数、Git HEAD）；
   - **单元 × 专家覆盖矩阵**：每个单元每个专家是 reviewed / sampled / skipped / missing；
   - 按模块/单元组织的发现：严重度、标题、`file:line`、证据、影响、修复摘要；
   - 跨单元的系统性问题（例如同类鉴权缺口、同类 SQL 注入、同类错误吞噬）；
   - 修复顺序（先 critical/high、同文件合并、根因优先）；
   - 覆盖缺口与未验证项，不能把 sampled/skipped 写成“全量已读”。
7. 运行 verdict 校验；contract 通过不等于质量通过。只有默认四位专家完整覆盖且所有单元都不是 skipped 时，才更新 latest。
8. 中文回复：一句话结论、覆盖情况（单元/专家）、最多 3 个重点发现（ID + file:line）、失败/缺失项、报告路径；不要粘贴整份报告。

## 子代理 prompts（逐字使用）

### perf
````text
你是 DSH Logistics 的 性能 专家（performance-analyst），只负责 perf 这一项。

## 范围
- 工作区：`C:/Users/hp/Desktop/LianYu-PC`
- 运行目录：`C:/Users/hp/Desktop/LianYu-PC/.logistics/runs/20261104-182459-9883f4`
- 先读 scope.json：`C:/Users/hp/Desktop/LianYu-PC/.logistics/runs/20261104-182459-9883f4/scope.json`
- handoff 输出：`C:/Users/hp/Desktop/LianYu-PC/.logistics/runs/20261104-182459-9883f4/handoff/perf.json`
- 本次审查**整个项目**，不是 diff，也不是最近一次提交：1100 个文件 / 12 个审查单元 / 135978 行。
- 先读 scope.json 的 `project.units`：每个单元都有 id、label、files、stats；必须逐单元检查，不能只挑几处。
- 单元文件很多时，用 `rg` 做跨文件模式扫描，再读关键文件；在 handoff.units 里写明每个单元的状态（reviewed/sampled/skipped）、实际读过的文件数和理由。
- 审查单元：`u01` backend/lianyu-service (1/3)；`u02` backend/lianyu-service (2/3)；`u03` frontend/src (1/2)；`u04` scripts (1/2)；`u05` backend/lianyu-dao；`u06` backend-agent；`u07` frontend/src (2/2)；`u08` backend/lianyu-web；`u09` frontend/electron；`u10` 其他模块（(root)、.github、admin 等）；`u11` backend（小模块合集 59 文件）；`u12` 其他模块（backend/lianyu-app、backend/lianyu-common、backend 等）。
- 不允许漏掉任何单元；漏掉会被机器判为无效 handoff。

## 必读 skill
调用 `skill` 读取 `logi-perf`，严格按其中的章节和证据标准执行。
不要加载其他专家 skill，不要调用其他 subagent。

## 覆盖要求
- 关注面：热路径、复杂度、数据库访问、运行时/阻塞、前端渲染与包体、内存与启动。
- 只能使用这些 section：hot-path / data-access / runtime / frontend / memory-startup。
- 每个 section 都必须检查并在 handoff 的 sections 中给出条数；0 表示“已检查且干净”，跳过则用 status=partial 并在 notes 说明，不要用 0 冒充已查。
- sections 的计数必须与 findings 实际条数逐项一致，否则 handoff 会被判为无效。
- handoff.units 必须覆盖 scope.project.units 的每一个 id；状态只能是 reviewed / sampled / skipped。
- `reviewed` = 读了该单元的关键文件并做了针对性检查；`sampled` = 因体量抽样但仍做了模式扫描；`skipped` = 未检查（会把整体状态降为 partial）。

## 证据与输出
- 每条 finding 必须包含：section、category、severity、confidence、title、file、line、evidence、impact、kind、fix。
- severity 只能是 critical / high / medium / low / info；critical/high 必须有可复现证据，否则会被降为低置信度。
- kind 只能是 direct（机械、低风险、可白名单内直接修）或 plan（需要人判断/设计）；性能问题一律 plan。
- fix 必须包含 summary、steps（3-6 步）、files、tests；没有文件或步骤的 direct 会被降为 plan。
- 最多 12 条发现；中文写 summary/impact/fix，代码和文件路径保持原样。

## Handoff
把 JSON 写到 `C:/Users/hp/Desktop/LianYu-PC/.logistics/runs/20261104-182459-9883f4/handoff/perf.json`，格式：
```json
{"schemaVersion":2,"expert":"perf","status":"ok|partial|failed","summary":"非空中文摘要","sections":{...},"units":{},"findings":[...],"deferred":[],"notes":""}
```
project 模式的 units 形如：
```json
{"u01":{"status":"reviewed","filesRead":12,"notes":"认证入口与 token 校验"},"u02":{"status":"sampled","filesRead":4,"notes":"按 rg 扫描后抽查"}}
```
先写文件，再结束回复；回复只需一句中文说明写了多少条发现。

## 约束
- 只读：不要修改源码、不要提交、不要删除文件。
- 只写运行目录下的 handoff；不要改动用户项目里的其他文件。
- 没有证据就不要报告；不要为了凑数报告推测。
- 忽略 `.logistics/` 和 `docs/logistics/` 里由本工具产生的文件。
````

## 收集命令（不是门禁）

```bash
"node" "C:/Users/hp/.dsh/profiles/desktop/node_modules/dsh-logistics/lib/cli/collect.mjs" --run "C:/Users/hp/Desktop/LianYu-PC/.logistics/runs/20261104-182459-9883f4"
```

## 主编材料命令

```bash
"node" "C:/Users/hp/.dsh/profiles/desktop/node_modules/dsh-logistics/lib/cli/bundle.mjs" --run "C:/Users/hp/Desktop/LianYu-PC/.logistics/runs/20261104-182459-9883f4"
```

## verdict 校验命令

```bash
"node" "C:/Users/hp/.dsh/profiles/desktop/node_modules/dsh-logistics/lib/cli/verdict.mjs" --run "C:/Users/hp/Desktop/LianYu-PC/.logistics/runs/20261104-182459-9883f4"
```

## 主编裁决格式

把最终裁决写到 `C:/Users/hp/Desktop/LianYu-PC/.logistics/runs/20261104-182459-9883f4/verdict.json`，机器只校验 contract，不替你判断内容：

```json
{
  "schemaVersion": 2,
  "runId": "20261104-182459-9883f4",
  "generatedAt": "ISO-8601",
  "coverage": {
    "expected": ["perf"],
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

写 `C:/Users/hp/Desktop/LianYu-PC/.logistics/runs/20261104-182459-9883f4/report.md`，中文，至少包含：

1. 一句话结论 + 本次覆盖范围（哪些模块跑了、哪些没跑）
2. 按模块组织的发现：模块名、严重度、标题、`file:line`、证据摘要、修复摘要
3. 建议修复顺序（同文件合并、根因优先；maintainability 单独批次）
4. 覆盖缺口 / 失败专家（如果有）
5. 跨专家 `deferred`：转交给谁、文件、原因；不能因为不在本次模块里就省略
6. 明确写“性能维度未运行，需要时执行 /logi-perf”；不要把未检查当成没问题

不要写健康分、评分、基线或门禁。报告是给人读的，不是 JSON 的复述。

本次是单模块或非默认组合：**不要**更新 latest；只写本次 run 的 report.md 与 verdict.json。

```json
{"runId":"20261104-182459-9883f4","reportPath":"C:/Users/hp/Desktop/LianYu-PC/.logistics/runs/20261104-182459-9883f4/report.md","verdictPath":"C:/Users/hp/Desktop/LianYu-PC/.logistics/runs/20261104-182459-9883f4/verdict.json","coverageComplete":true}
```

单模块、非默认组合，或有 missing/invalid/failed 时，**不要**更新 latest；在最终回复里说明原因。


## 失败与重试

- 子代理超时、报错、未写 handoff、漏单元：重试一次，prompt 中明确要求“先覆盖全部 units 再写文件”。
- 两次失败：写失败 handoff，继续；报告必须列出该专家和单元未覆盖。
- 不要因为某个专家失败就伪造 findings；也不要让整次运行卡死。

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
