import { readFileSync, writeFileSync } from 'node:fs'

const runDir = 'C:/Users/hp/Desktop/LianYu-PC/.logistics/runs/20261105-184743-a813e2-master'
const scope = JSON.parse(readFileSync(`${runDir}/scope.json`, 'utf8'))
const handoffs = JSON.parse(readFileSync(`${runDir}/handoffs.json`, 'utf8'))
const raw = JSON.parse(readFileSync(`${runDir}/findings-compact.json`, 'utf8'))

const unitMap = new Map()
for (const unit of scope.project.units) {
  for (const file of unit.files ?? []) unitMap.set(file.path, unit.id)
}

const byId = new Map(raw.map((finding) => [finding.id, finding]))
byId.get('MNT-2D162').module = 'u05'

byId.set('REL-5D862', {
  id: 'REL-5D862',
  expert: 'reliability',
  module: 'u10',
  section: 'tests',
  severity: 'high',
  confidence: 'high',
  title: 'CI 只跑两条腿：Python 与 admin 测试缺位，前端还并行跑',
  brief: '本地要求四套测试全绿，但 CI 只跑 Java 和前端；Python 1200 多个测试与 admin 后台从未在流水线里跑过，前端还用并行模式，已知的偶发失败会被当成真红。',
  file: '.github/workflows/ci.yml',
  line: 25,
  kind: 'plan',
  evidence: 'ci.yml 只有 3 个 job：backend（`mvn -B -ntp test`，21-22 行）、frontend（`npx vitest run`，39 行）、deps-audit（`continue-on-error: true`），没有 pytest job，也没有 admin job。按 scope 命令在仓库根执行 `pytest -q backend-agent`：48 个收集错误，全部为 `ModuleNotFoundError: No module named \'app\'`；进入 backend-agent 目录执行则报 `ModuleNotFoundError: No module named \'respx\'`（dev 依赖未装）。backend-agent/pyproject.toml 的 [tool.pytest.ini_options] 只有 `asyncio_mode = "auto"` 与 `testpaths = ["tests"]`，无 pythonpath、无 conftest.py。frontend 的 `npx vitest run` 未加 `--no-file-parallelism`，而 AGENTS.md §4 明确记录 updater 并发用例在满载并行下会偶发红。admin/package.json:9 的 `"test": "vitest --passWithNoTests"` 只在本地手工执行，CI 从未安装 admin 依赖。',
  impact: 'Python 侧 1200 多个错误处理、熔断和流式契约用例，以及 admin 后台的登录/会话逻辑，都没有自动化回归保护；前端已知 flake 会让 CI 随机红灯，团队逐渐把红灯当噪声，真回归更容易漏过。',
  fix: {
    summary: '让四套测试都在 CI 里真实执行：补 pytest 与 admin job，前端改为串行，并让空套件不再算通过。',
    steps: [
      '在 backend-agent/pyproject.toml 增加 `pythonpath = ["."]`（或在 backend-agent/conftest.py 插入 sys.path），使 `pytest -q backend-agent` 能从仓库根收集。',
      'CI 新增 backend-agent job：setup-python 3.12 + `pip install -e backend-agent --group dev`（或 uv sync --group dev），执行 `cd backend-agent && pytest -q`，并安装 respx。',
      'CI 新增 admin job：工作目录 admin，`npm ci` 后执行 `npm test`；同时把 admin/package.json 的 `--passWithNoTests` 去掉，空套件必须失败。',
      '把 ci.yml:39 改为 `npx vitest run --no-file-parallelism`，并把 frontend/package.json 的 test 脚本固定为同一口径。',
      '把 pytest、admin、frontend 三个 job 设为必需检查，并在 AGENTS.md §4 同步命令，避免文档与流水线继续漂移。',
    ],
    files: ['.github/workflows/ci.yml', 'backend-agent/pyproject.toml', 'frontend/package.json', 'admin/package.json'],
    tests: ['pytest -q backend-agent', 'npm --prefix frontend test', 'npm --prefix admin test'],
  },
})

const droppedIds = new Set(['REL-E07C8', 'MNT-F7190'])
const findings = raw
  .filter((finding) => !droppedIds.has(finding.id))
  .map((finding) => {
    const copy = JSON.parse(JSON.stringify(finding))
    copy.module = unitMap.get(copy.file) || copy.module || 'u10'
    copy.brief = String(copy.brief).replace(/\s+/g, ' ').trim()
    copy.title = String(copy.title).replace(/\s+/g, ' ').trim()
    copy.evidence = String(copy.evidence).replace(/\s+/g, ' ').trim()
    copy.impact = String(copy.impact).replace(/\s+/g, ' ').trim()
    copy.fix.summary = String(copy.fix.summary).replace(/\s+/g, ' ').trim()
    copy.fix.steps = copy.fix.steps.map((step) => String(step).replace(/\s+/g, ' ').trim())
    return copy
  })

const order = { critical: 0, high: 1, medium: 2, low: 3, info: 4 }
const expertOrder = { security: 0, correctness: 1, reliability: 2, maintainability: 3, perf: 4 }
findings.sort((a, b) => order[a.severity] - order[b.severity] || expertOrder[a.expert] - expertOrder[b.expert] || a.id.localeCompare(b.id))

const dropped = [
  {
    id: 'REL-E07C8',
    expert: 'reliability',
    title: 'CI 里前端测试按并行执行，已知会随机失败的 updater 用例没有串行保护',
    file: '.github/workflows/ci.yml',
    line: 39,
    reason: '与 REL-5D862 同根因：都是 CI 测试 job 配置缺失/错误；前端串行参数已并入 REL-5D862 的修复步骤与验收，保留覆盖更全的那条。',
  },
  {
    id: 'MNT-F7190',
    expert: 'maintainability',
    title: 'CI 只跑 backend 与 frontend，没有 pytest 与 admin job，前端还用了与 AGENTS.md 冲突的并行跑法',
    file: '.github/workflows/ci.yml',
    line: 39,
    reason: '与 REL-5D862 同根因：CI 测试矩阵不完整；admin/pytest/前端串行三条已合并进 REL-5D862，避免同一文件同一问题重复计数。',
  },
]

const relations = [
  {
    from: 'SEC-59BE5',
    to: 'SEC-3C38A',
    type: 'related',
    reason: '匿名 /api/agent/ 入口让 SSRF 从“需要内网权限”变成“公网可直接触发”，两条必须一起修。',
  },
  {
    from: 'REL-0486A',
    to: 'REL-438AC',
    type: 'related',
    reason: '同一条客户端断开链路：Java 侧不取消上游，Python 侧把取消计成断路器失败。',
  },
  {
    from: 'COR-DFF3C',
    to: 'COR-42F8E',
    type: 'related',
    reason: '前后端两套嵌套括号处理，用户看到的文本与 TTS 读出的内容不一致。',
  },
  {
    from: 'SEC-D0C34',
    to: 'MNT-695AC',
    type: 'same-file',
    reason: '同一批 scripts/ 运维脚本：一条讲 SSH 连接安全，一条讲脚本堆积、重复与硬编码路径。',
  },
  {
    from: 'SEC-061DE',
    to: 'REL-5D862',
    type: 'same-file',
    reason: '同一个 ci.yml：依赖扫描放行与测试 job 缺位是两条独立缺口。',
  },
  {
    from: 'REL-5D862',
    to: 'REL-35BDA',
    type: 'related',
    reason: 'CI 不跑 admin 测试，而 admin 自身又是空套件，两层缺口互相放大。',
  },
  {
    from: 'REL-3785E',
    to: 'REL-438AC',
    type: 'related',
    reason: '都在处理流式断开/失败后的收尾：一个不落库，一个不取消上游。',
  },
]

const verdict = {
  schemaVersion: 2,
  runId: scope.runId,
  editor: 'main-session',
  generatedAt: new Date().toISOString(),
  coverage: {
    expected: scope.experts,
    received: scope.experts,
    missing: [],
    invalid: [],
    failed: [],
    partial: [],
    complete: true,
  },
  findings,
  deferred: handoffs.deferred,
  dropped,
  relations,
}

writeFileSync(`${runDir}/verdict.json`, `${JSON.stringify(verdict, null, 2)}\n`, 'utf8')

function statusCell(expert, unitId) {
  const unit = handoffs.handoffs.find((item) => item.expert === expert)?.units?.[unitId]
  if (!unit) return '—'
  return `${unit.status === 'reviewed' ? '审阅' : unit.status === 'sampled' ? '抽样' : unit.status} ${unit.filesRead ?? unit.files?.length ?? 0}`
}

const matrixRows = scope.project.units.map((unit) => {
  const cells = ['correctness', 'security', 'reliability', 'maintainability'].map((expert) => statusCell(expert, unit.id))
  return `| ${unit.id} | ${unit.label} | ${unit.stats.files}/${unit.stats.lines} | ${cells.join(' | ')} |`
}).join('\n')

const severityCount = findings.reduce((acc, finding) => {
  acc[finding.severity] = (acc[finding.severity] ?? 0) + 1
  return acc
}, {})
const direct = findings.filter((finding) => finding.kind === 'direct')
const plan = findings.filter((finding) => finding.kind === 'plan')

const findingBlocks = findings.map((finding) => {
  const unit = unitMap.get(finding.file) || finding.module
  return [
    `- [${finding.severity}] [${unit}] [${finding.kind}] ${finding.id} — ${finding.title}`,
    `  简述：${finding.brief}`,
    `  详细问题：${finding.title}`,
    `  位置：${finding.file}:${finding.line} · 证据：${finding.evidence}`,
    `  影响：${finding.impact} · 修复：${finding.fix.summary}`,
  ].join('\n')
}).join('\n\n')

const directList = direct.map((finding) => `- ${finding.id}（${finding.module}）— ${finding.brief}`).join('\n')
const planList = plan.map((finding) => `- ${finding.id}（${finding.module}）— ${finding.brief}`).join('\n')

const report = `# 予念（LianYu-PC）全项目审查报告

## 一句话结论

本次审查覆盖 12 个单元、1100 个文件、135978 行，确认 32 条可操作问题：1 条 critical、6 条 high、16 条 medium、9 条 low。最紧急的是 backend-agent 的 /agent/* 端点可被公网匿名调用；其次是 Python 出站无 SSRF 校验、客户端断开污染共享断路器、上游流不取消继续计费。整体不是“到处都烂”，而是三条运行时边界在 agent 入口处失守、若干状态一致性缺口、以及测试体系与 CI 脱节。

## 覆盖情况

- 运行：main / HEAD c652e14，project 模式，1100 个已审文件，12 个单元，144 个核心文件全部命中。
- 四位默认专家全部完成：correctness 6 条、security 7 条、reliability 10 条、maintainability 11 条；合并同根因 CI 两条后保留 32 条，dropped 2 条，deferred 9 条。
- 性能维度未运行（not-selected）；需要性能结论时另跑 \`/logi-perf\`。
- 覆盖状态：u01–u12 均无 skipped；审阅与抽样的分布见下表，抽样单元仍满足每单元至少 30 个文件。

| 单元 | 范围 | 文件/行 | 正确性 | 安全 | 健壮性 | 可维护性 |
|---|---|---:|---|---|---|---|
${matrixRows}

## 发现

以下按 critical → high → medium → low 排序，不按单元分节。每条保留原始“详细问题”标题，证据均来自源码或已复现的测试。

${findingBlocks}

## 可直接修

以下为 [可直接修] 项；这些改动边界清楚、无需产品决策，可按条目直接开修。

${directList}

## 需方案

以下为 [需方案] 项；涉及协议、数据迁移、跨端契约或产品取舍，先定方案再动手。

${planList}

## 测试证据

按 scope 的四条 canonical 命令逐条记录：

1. \`npm --prefix admin test\`：failed（未真正运行）。admin 没有安装 node_modules，命令返回 \`'vitest' 不是内部或外部命令\`；因此 admin 的 1 个测试文件也没有被执行。这不是产品代码失败，而是测试环境缺失。
2. \`mvn -q -f backend/pom.xml test\`：passed。531 个测试，0 failure、0 error，覆盖 10 个 Maven 模块。
3. \`pytest -q backend-agent\`：failed（未真正运行）。从仓库根执行出现 48 个 collection error，均为 \`ModuleNotFoundError: No module named 'app'\`；进入 backend-agent 后仍缺 aiomysql/respx。security 专家用 backend-agent/.venv 跑出 1157 passed，但这不是 scope 指定的 canonical 命令，不能作为四条门禁已通过的证据。
4. \`npm --prefix frontend test\`：passed。71 个测试文件、417 个用例全绿；本地未触发 updater 的并行 flake，但 CI 仍在用并行模式（见 REL-5D862）。

补充：REL-5D862 本身就是“Python 测试不在 CI 且根目录跑不通”，因此第 3 条的失败既是测试环境问题，也是被审查出来的高优先级缺口，不应被“换个 venv 就能过”掩盖。

## 修复顺序

1. P0（当天）：先修 SEC-59BE5 与 SEC-3C38A。前者把 /api/agent/ 收回鉴权并补内部签名，后者在 Python 出站客户端做与 Java 等价的白名单 + 解析后 IP 校验；两条一起修，否则 SSRF 仍可被匿名触发。
2. P0（当天）：修 REL-0486A 与 REL-438AC。把 CancelledError/GeneratorExit 排除出熔断失败，并在 Java 侧保存 Disposable、客户端断开时 dispose 上游流；这两条直接决定“一个用户切页会不会拖垮所有人”。
3. P1（本周）：修 COR-837C2 与 COR-DFF3C。前者统一 Java/Python 的 source_hash 口径并迁移历史行；后者把嵌套括号改成区间合并后一次性拼装，先补三组已复现的用例。
4. P1（本周）：修 SEC-D0229、SEC-D0C34、SEC-061DE。分别处理 Jasypt 明文回退、运维脚本 SSH 主机密钥校验、CI 依赖扫描放行与 action 固定 SHA。
5. P2（本迭代）：处理状态一致性（COR-EF936、COR-28242、REL-2959C、REL-3785E）与前端失败可见性（REL-A64C9、REL-7FC42）。这些需要迁移、并发控制或产品语义确认，不宜和 P0 混在一个提交里。
6. P3（排期）：处理可维护性项，优先 MNT-75E6F（main.js 拆分）与 MNT-9B09F（SSE 单一实现 + golden 帧），其余脚本清理、i18n、admin 格式化按维护窗口分批做。

## 跨单元系统性问题

1. 运行时边界在 agent 入口失守：SEC-59BE5 让 /api/agent/* 绕过 Sa-Token；SEC-3C38A 又让请求体里的 baseUrl 直达内网。两条合起来，Java 侧的 OutboundUrlValidator 和 SsrfPinningClientFactory 等于只保护了 Java 自己的出站，没有保护 Python 运行时。
2. 断链路的两次记账错误：REL-438AC 让 Java 断开后上游继续生成，REL-0486A 又把 Python 侧收到的取消计成断路器失败；一次用户切页会同时造成“白烧 token”和“污染共享熔断器”。
3. 同一语义多套实现：前后端各有一套内心独白剥离（COR-DFF3C/COR-42F8E），SSE 解析有三份（MNT-9B09F），启动 profiler 有两份（MNT-020D0），语言清单有四份（MNT-B0A65）。这不是风格问题，而是修复时容易只改一边、线上表现不一致。
4. 状态一致性缺少数据库兜底：记忆 source_hash 碰撞（COR-837C2）、relationship 整行覆盖（COR-EF936）、消息 seq 只靠 Redis（COR-28242）都属于“进程内看起来对，换实例/并发就丢”的问题。
5. 测试体系与 CI 脱节：Python 1200+ 用例、admin 后台、前端串行口径都没有进入 CI；这使上述高风险修复缺少回归保护（REL-5D862/REL-35BDA）。
6. 运维脚本既是安全面也是卫生面：114 个脚本关闭 SSH 主机密钥校验并以 root 直连生产（SEC-D0C34），同时 97 个下划线脚本、7 个部署变体、109 处硬编码 IP 留在仓库（MNT-695AC）。两条同文件但根因不同，需要一起清理。

## 覆盖缺口与未验证

- 性能维度未运行：本次没有跑 /logi-perf，不能对启动耗时、热路径复杂度、包体、内存增长下结论。
- admin 与 pytest 的 canonical 命令没有真正跑通：admin 缺 node_modules；pytest 从仓库根 collection 失败，backend-agent 内缺 aiomysql/respx。已用 .venv 得到 1157 passed 作为参考，但不替代 canonical 结果。
- 抽样单元：correctness 的 u04/u09/u10/u12、security 的 u01–u04/u07/u09、reliability 的 u02/u04/u05/u07/u10–u12、maintainability 的 u01–u07 为 sampled；每个单元至少读了 30 个文件且 core 全覆盖，但未逐行审阅全部 1100 个文件。
- 两个 dropped 项（REL-E07C8、MNT-F7190）与 9 条 deferred 未在报告正文展开；deferred 含 backend-agent 直写 MySQL、moments 评论锁 owner token、衰减轮转饥饿、脚本 get_pty、Sa-Token 30 天等，已在 verdict.json 保留供后续分派。
- 未验证项：没有做真实公网渗透、没有对生产 Docker 网络做 SSRF 实测、没有跑打包后的 Electron 冒烟；SEC-59BE5/SEC-3C38A 的证据来自源码路径与本地契约，不是生产利用结果。

## 专家报告索引

- [正确性审查报告](handoff/correctness/correctness.md) — 6 条，含记忆 hash、嵌套括号、关系并发、消息 seq。
- [安全审查报告](handoff/security/security.md) — 7 条，含 agent 匿名入口、SSRF、Jasypt、运维脚本、CI 供应链。
- [健壮性审查报告](handoff/reliability/reliability.md) — 10 条，含断路器污染、上游不取消、刷新令牌误判、群聊无失败出口。
- [可维护性审查报告](handoff/maintainability/maintainability.md) — 11 条，含 main.js 上帝模块、i18n 漂移、SSE 三实现、scripts 堆积。

---

需要我帮你开始直接修复吗？还是你拿上报告去新开一个会话施行修复？
`

writeFileSync(`${runDir}/report.md`, report, 'utf8')
console.log(`findings=${findings.length} direct=${direct.length} plan=${plan.length} severity=${JSON.stringify(severityCount)}`)
console.log(`reportChars=${report.length}`)
