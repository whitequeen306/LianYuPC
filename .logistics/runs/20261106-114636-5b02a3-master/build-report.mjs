import fs from 'node:fs';

const runDir = 'C:/Users/hp/Desktop/LianYu-PC/.logistics/runs/20261106-114636-5b02a3-master';
const v = JSON.parse(fs.readFileSync(`${runDir}/verdict.json`, 'utf8'));
const scope = JSON.parse(fs.readFileSync(`${runDir}/scope.json`, 'utf8'));

const EXPERT_CN = { correctness: '正确性', security: '安全', reliability: '健壮性', maintainability: '可维护性', perf: '性能' };
const STATUS_CN = { reviewed: 'R', sampled: 'S', skipped: '—' };
const SEV_CN = { critical: '严重', high: '高', medium: '中', low: '低' };

const clean = (s) => String(s).replace(/\s+/g, ' ').trim();
function clip(s, n) {
  const t = clean(s);
  if (t.length <= n) return t;
  const cut = t.slice(0, n);
  const p = Math.max(cut.lastIndexOf('。'), cut.lastIndexOf('；'), cut.lastIndexOf('，'));
  return (p > n * 0.55 ? cut.slice(0, p + 1) : cut + '…') + '（详见专家报告）';
}

const counts = v.findings.reduce((a, f) => ((a[f.severity] = (a[f.severity] || 0) + 1), a), {});
const kindCounts = v.findings.reduce((a, f) => ((a[f.kind] = (a[f.kind] || 0) + 1), a), {});

const lines = [];
const push = (...xs) => lines.push(...xs);

push('# 予念（LianYu-PC）全项目审查报告', '');
push(`- 运行：\`${scope.runId}\``);
push('- 范围：整个仓库（非 diff），分支 `main`，HEAD `c652e14`，1108 个文件 / 139994 行');
push(`- 专家：5 位（正确性 / 安全 / 健壮性 / 可维护性 / 性能），12 个单元，共 ${v.findings.length} 条发现（critical ${counts.critical || 0} / high ${counts.high || 0} / medium ${counts.medium || 0} / low ${counts.low || 0}）`);
push(`- 结论口径：本报告只描述证据与影响，不给健康分、不做门禁判定；所有结论均可回溯到 \`file:line\` 与专家报告。`, '');

push('## 一句话结论', '');
push('这套「Electron + Java + Python」三层分层的骨架是清楚的，真正的问题集中在两类：**边界上的默认值**和**异常路径的收尾**。前者让公网 `/api/agent/*` 在缺少身份头时直接放行、让调用方自带模型地址、让缺主密钥时退回明文、让更新器在无公钥时跳过验签；后者让流式 `[DONE]` 吞掉工具调用、让客户端中断打开熔断、让 SSE 收尾异常把回复卡到 30 分钟超时、让群聊失败静默缺席。50 条发现里 1 条 critical、10 条 high——没有一条需要推翻架构，但 P0 的两条必须在下次公网发布前修掉。', '');
push('范围与方法：5 位专家各自只读审查同一份 12 单元切分，独立给证据、独立跑测试；主编逐条复核了 critical/high 的源码与复现描述，并按严重度重新排序、合并关系。专家原始报告保留在 `handoff/<expert>/<expert>.md`，本报告不替代它们。', '');

push('## 覆盖情况', '');
push('12 个单元 × 5 位专家全部覆盖，无 skipped；`R` = reviewed（通读核心文件）、`S` = sampled（按核心清单抽样）、`—` = 未覆盖。每格为该专家在本单元实际读过的文件数。', '');
push('| 单元 | 主题 | 文件数 | 正确性 | 安全 | 健壮性 | 可维护性 | 性能 |');
push('|---|---|---:|---|---|---|---|---|');
for (const u of v.coverage.units) {
  const cells = scope.experts.map((e) => {
    const s = u.byExpert[e];
    return s ? `${STATUS_CN[s.status] || s.status} ${s.filesRead}` : '—';
  });
  push(`| ${u.id} | ${clean(u.title)} | ${u.files} | ${cells.join(' | ')} |`);
}
push('', `覆盖结论：5/5 专家、12/12 单元，全部单元达到 30 文件下限并覆盖 core 清单；u04（scripts）、u09（electron）、u10（root/.github/admin）、u12（backend 小模块）没有专家标 reviewed，只做了抽样——这四块是本轮结论最薄的地方，已在「覆盖缺口」里单列。`, '');

push('## 发现', '');
push(`共 ${v.findings.length} 条，按严重度排序（critical → high → medium → low），同级别内按专家顺序。每条四行：简述 / 详细问题 / 位置与证据 / 影响与修复。文末按 [可直接修] 与 [需方案] 两类分别给出清单。`, '');

for (const sev of ['critical', 'high', 'medium', 'low']) {
  const group = v.findings.filter((f) => f.severity === sev);
  if (!group.length) continue;
  push(`### ${SEV_CN[sev]}（${group.length}）`, '');
  for (const f of group) {
    push(`- [${f.severity}] [${f.unit}] [${f.kind}] ${f.id} — 简述：${f.brief}`);
    push(`详细问题：${clean(f.title)}。`);
    const loc = /token|api[_-]?key|secret/i.test(f.file) ? `${f.file} 第 ${f.line} 行` : `${f.file}:${f.line}`;
    push(`位置：${loc} · 证据：${clip(f.evidence, 300)}`);
    push(`影响：${clip(f.impact, 200)} · 修复：${clean(f.fix.summary)}`);
    push('');
  }
}

push('## 专家报告索引', '');
for (const e of scope.experts) {
  const n = v.findings.filter((f) => f.expert === e).length;
  push(`- [${e}.md](handoff/${e}/${e}.md) — ${EXPERT_CN[e]}，${n} 条发现`);
}
push('');

push('## 可直接修', '');
push('证据已闭合、改动局部、不需要先做方案评审的项（按严重度）。每条都带复现路径或明确断言，修完即可用对应测试锁定。', '');
for (const f of v.findings.filter((x) => x.kind === 'direct')) {
  push(`- ${f.id} [${f.severity}] [${f.unit}] — ${clean(f.brief)}`);
}
push('');

push('## 需方案', '');
push('需要先定方案（数据迁移、限流口径、线程模型、索引与前端架构），不宜在热修里顺手做。', '');
for (const f of v.findings.filter((x) => x.kind === 'plan')) {
  push(`- ${f.id} [${f.severity}] [${f.unit}] — ${clean(f.brief)}`);
}
push('');

push('## 测试证据', '');
push('四条门禁命令逐条记录；`主编实跑` 指本轮在仓库根目录直接执行，`专家实跑` 指该专家在 handoff 中声明的执行结果。', '');
push('| 命令 | 结果 | 证据 |');
push('|---|---|---|');
push('| `npm --prefix admin test` | **failed**（exit 1） | 主编实跑：`\'vitest\' 不是内部或外部命令`，`admin/node_modules` 不存在。correctness 在 handoff 里写 passed，与实测不符，本报告按实测记 failed。 |');
push('| `mvn -q -f backend/pom.xml test` | **passed** | 4 位专家实跑一致：各模块编译并跑完单测，`BUILD SUCCESS`；日志里的 WARN/ERROR 来自负路径断言（鉴权失败、网关异常），不是构建失败。 |');
push('| `pytest -q backend-agent` | **failed**（干净环境）/ 1157 passed（`.venv`） | 主编实跑：根目录 48 个 collection error（exit 2），缺 `aiomysql` 等依赖与 `pythonpath`。security / reliability 在 `backend-agent/.venv` 内实跑为 1157 passed；两者不矛盾，说明命令本身不可移植（REL-F9370）。 |');
push('| `npm --prefix frontend test` | **passed** | perf 实跑 76 files / 460 tests；maintainability 报 75 / 459，差异来自运行时的文件筛选，均 exit 0。 |');
push('', '补充：correctness 与 reliability 各自用 MockTransport / 单测复现了 C-001 与 REL-34EFB；这两条不是纸面推断。admin 的「passed」是本轮唯一一处专家声明与实测冲突，已按实测纠正，并据此保留 REL-BC684 / MNT-10A82。', '');

push('## 跨单元系统性风险', '');
push('单条发现修完只是止血，下面五组是同一类土壤长出来的，建议按组处理：', '');
push('1. **agent 边界默认放行**（SEC-EA2CB / SEC-D6178 / SEC-E06CB，结构土壤见 MNT-258C6）：`main.py` 25 个端点复制同一段 `if user_id_header and ...` 样板，缺头就落到 body；网关 `location /api/agent/` 只限流不鉴权。三条发现是同一扇门的不同利用方式，只修其中一条等于没修。');
push('2. **异常路径把失败记成成功**（COR-559B1 / REL-34EFB / REL-42BC8 / REL-49628）：流式 `[DONE]` 提前 return、`GeneratorExit` 记成上游故障、SSE 收尾只 catch IOException、event 队列无死信无 try/catch——共同点是「结束时没人负责收尾」，表现为工具不执行、熔断误开、回复不落库、通知静默丢。');
push('3. **同一事实多处写、无幂等**（COR-CA186 / COR-B3226 / COR-50DF0 / REL-388F4）：记忆 sourceHash 冲突覆盖、新建记忆重复入队、outbox 卡 RUNNING 无人回收、后台重试预算短于熔断窗口——记忆链路的写入端和投影端各自假设对方会兜底。');
push('4. **危险默认值**（SEC-A3F36 / SEC-701CA / SEC-CA474 / REL-BC684）：默认信任 XFF、缺主密钥退回明文、无公钥跳过清单验签、`--passWithNoTests` 让空套件也绿。四条都是「配置缺失时选择了更宽松的一侧」，与 AGENTS.md §3.3「默认取最保守的一侧」直接冲突。');
push('5. **前端首屏与轮询成本**（PRF-9225F / PRF-D16A1 / PRF-F4602 / PRF-2EAFA）：element-plus 全量注册让 launcher/quick 入口静态背上 1.1MB chunk，聊天页无虚拟滚动、10s 轮询拉 50 条全文、上拉无上限——桌宠与快捷聊是常驻窗口，这部分成本是持续付的。');
push('');

push('## 修复顺序', '');
push('**P0（公网发布前必须）**：SEC-EA2CB 网关侧加 `auth_request` 或内部签名，并在 agent 端点强制身份（缺头 401，不再回退 body）；SEC-701CA 缺主密钥时拒绝加密/启动失败，同时轮换已明文落库的 Key。');
push('**P1（本周）**：COR-559B1（`return`→`break`）、REL-34EFB（只对真实上游错误计熔断）、REL-42BC8（收尾 catch 扩到 Exception 并保证 complete）、SEC-E06CB（baseUrl 白名单 + 禁回显）、SEC-A3F36（默认不信任 XFF，按部署配置显式开启）。');
push('**P2（两周）**：PRF-71D14（群聊内外层线程池隔离）、COR-CA186 + COR-B3226（记忆 hash 与入队去重）、COR-50DF0 + REL-388F4（outbox 回收定时 + 重试预算 ≥ 熔断窗口）、SEC-D6178（桌面感知独立鉴权 + 配额）、SEC-1101A（TOTP 失败计数与锁定）。');
push('**P3（迭代）**：MNT-258C6（main.py 拆 router，顺手把鉴权收成一处）、PRF-9225F（element-plus 按需）、PRF-D16A1 / PRF-F4602（虚拟滚动 + afterSeq 增量）、索引类 PRF-9FA45 / PRF-5C4A1、清理类 MNT-745BE / MNT-B9294 / MNT-10A82。');
push('');

push('## 覆盖缺口', '');
push('- **深度**：每单元实际读 30–41 个文件，相对 1108 个文件仍是抽样；u04 / u09 / u10 / u12 无 reviewed，只有 sampled，结论强度弱于其余单元。');
push('- **运行态**：未连生产库做 `EXPLAIN` 或压测，性能条目的数据库成本是索引与 SQL 形态的静态推导（PRF-9FA45 / PRF-454DD / PRF-5C4A1 尤其需要真实执行计划确认）。');
push('- **测试可信度**：admin 套件本轮从未真正执行（依赖缺失）；`pytest -q backend-agent` 在干净环境不可跑，1157 passed 依赖 `.venv`；这两点会让「本地全绿」高估真实覆盖率。');
push('- **未验证**：未做浏览器/E2E、未验证生产 nginx 实际配置与 `.env`、未验证 MinIO 更新资产与签名链、未做并发压测（群聊死锁由线程模型推导 + 代码路径确认，未在真实负载下复现）。');
push('');

push('## 转交与保留', '');
push(`专家之间转交但未计入上述 ${v.findings.length} 条的观察，共 ${v.deferred.length} 条；它们不是「没问题」，而是证据强度或归属暂不构成本轮发现：`, '');
for (const d of v.deferred) {
  const loc = d.file ? ` · \`${d.file}${d.lines ? ':' + d.lines : ''}\`` : '';
  push(`- ${d.from} → ${d.expert || '（无）'}${loc} — ${clean(d.summary)}`);
}
push('');

push('## 关系图', '');
push('[graph.html](graph.html) 由 verdict.relations 生成，标出同文件、同根因、依赖与相关四类边；本轮 10 条关系里最值得先看的是 `SEC-EA2CB ↔ SEC-D6178`（同一扇门）与 `PRF-9FA45 ↔ PRF-5C4A1`（同一张表的两条慢查询）。', '');

fs.writeFileSync(`${runDir}/report.md`, lines.join('\n'), 'utf8');
console.log('report chars', lines.join('\n').length, 'blocks', v.findings.length);
