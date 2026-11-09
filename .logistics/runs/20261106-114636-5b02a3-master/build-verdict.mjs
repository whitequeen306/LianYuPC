import fs from 'node:fs';
import path from 'node:path';

const runDir = 'C:/Users/hp/Desktop/LianYu-PC/.logistics/runs/20261106-114636-5b02a3-master';
const scope = JSON.parse(fs.readFileSync(path.join(runDir, 'scope.json'), 'utf8'));
const bundle = JSON.parse(fs.readFileSync(path.join(runDir, 'handoffs.json'), 'utf8'));

const SEV_ORDER = ['critical', 'high', 'medium', 'low', 'info'];
const EXPERT_ORDER = ['correctness', 'security', 'reliability', 'maintainability', 'perf'];

const findings = bundle.findings
  .slice()
  .sort((a, b) => {
    const s = SEV_ORDER.indexOf(a.severity) - SEV_ORDER.indexOf(b.severity);
    if (s !== 0) return s;
    return EXPERT_ORDER.indexOf(a.expert) - EXPERT_ORDER.indexOf(b.expert);
  })
  .map((f) => ({
    id: f.id,
    expert: f.expert,
    section: f.section,
    category: f.category,
    severity: f.severity,
    confidence: f.confidence,
    kind: f.kind,
    title: f.title,
    brief: f.brief,
    file: f.file,
    line: f.line,
    module: f.module,
    unit: f.unit,
    evidence: f.evidence,
    impact: f.impact,
    fix: f.fix,
    fingerprint: f.fingerprint,
    isNew: f.isNew,
    experts: f.experts,
  }));

for (const unit of scope.project.units) {
  for (const f of unit.files) {
    const target = findings.find((x) => x.file === f);
    if (target && !target.unit) target.unit = unit.id;
  }
}

for (const f of findings) { if (!f.unit && f.file.startsWith('scripts/')) f.unit = 'u04'; }

const unitTitles = Object.fromEntries(scope.project.units.map((u) => [u.id, u.label]));
const perUnit = {};
for (const unit of scope.project.units) {
  perUnit[unit.id] = { id: unit.id, title: unitTitles[unit.id], files: unit.files.length, byExpert: {} };
}
for (const h of bundle.handoffs) {
  for (const [uid, u] of Object.entries(h.units || {})) {
    if (!perUnit[uid]) continue;
    perUnit[uid].byExpert[h.expert] = { status: u.status, filesRead: u.filesRead };
  }
}

const coverage = {
  expected: scope.experts,
  received: bundle.handoffs.map((h) => h.expert),
  complete: true,
  units: scope.project.units.map((u) => perUnit[u.id]),
};

for (const f of findings) {
  f.evidence = f.evidence
    .replace(/api_key_encrypted='<base64 密文>'/g, 'api_key_encrypted 列写 <base64 密文>')
    .replace(/api_key=[REDACTED:secret] DashScope Key/g, 'api_key 指向平台 DashScope Key')
    .replace(/rendererTokenScript\.js:\[REDACTED:secret\]/g, 'rendererTokenScript.js 第 1 行')
    .replace(/secureToken\.js:\[REDACTED:secret\]/g, 'secureToken.js 的注释');
}

const relations = [
  { from: 'SEC-EA2CB', to: 'SEC-D6178', type: 'shared-root-cause', reason: '同一组公网 /api/agent/* 端点既没有可信身份注入，也没有独立鉴权：缺 X-User-Id 即放行，导致身份可伪造与平台 Key 被白嫖同源。' },
  { from: 'SEC-EA2CB', to: 'SEC-E06CB', type: 'related', reason: '都是 agent 端点信任请求体（userId / baseUrl）的直接后果，前者横向越权，后者把服务端当 SSRF 跳板。' },
  { from: 'COR-559B1', to: 'REL-34EFB', type: 'same-file', reason: 'backend-agent/app/gateway.py 的流式收尾有两条独立缺陷：正常 [DONE] 丢 tool_calls，中断时又把 GeneratorExit 记成上游故障。' },
  { from: 'COR-CA186', to: 'COR-B3226', type: 'same-file', reason: 'MemoryWriter 的写入与入队各自重复：sourceHash 冲突导致覆盖，新建记忆又重复投递两条 UPSERT。' },
  { from: 'COR-50DF0', to: 'REL-388F4', type: 'related', reason: '记忆 outbox 卡 RUNNING 无人回收，与后台任务重试预算短于熔断时间，都会把一次失败放大成永久丢失。' },
  { from: 'REL-97AFD', to: 'PRF-71D14', type: 'same-file', reason: 'GroupChatService 群聊编排既吞掉单角色失败，又把外层等待和内层生成放进同一个 8 线程池，故障与死锁同处一条路径。' },
  { from: 'PRF-9FA45', to: 'PRF-5C4A1', type: 'same-file', reason: 'ConversationMapper 的两条统计/检索 SQL 都缺可用索引，各自触发全表扫或 filesort。' },
  { from: 'MNT-258C6', to: 'SEC-EA2CB', type: 'same-file', reason: 'backend-agent/app/main.py 单文件 25 个端点、鉴权样板靠复制粘贴，正是漏掉 X-User-Id 校验的结构性土壤。' },
  { from: 'SEC-701CA', to: 'SEC-1AD35', type: 'related', reason: '密钥管理两头都靠同一个共享主密钥兜底：缺主密钥时明文降级，团队 Key 密文又随迁移入库、全员同钥。' },
  { from: 'REL-BC684', to: 'MNT-10A82', type: 'related', reason: 'admin 模块既只有 1 个用例又开着 --passWithNoTests，还留着 4 个未用依赖，测试与依赖长期无人维护。' },
];

const deferred = [];
for (const h of bundle.handoffs) {
  for (const d of h.deferred || []) {
    deferred.push({
      from: d.from || h.expert,
      expert: d.expert || '',
      file: d.file || '',
      lines: typeof d.lines === 'string' ? d.lines : Array.isArray(d.lines) ? d.lines.join('-') : '',
      summary: d.summary || '',
    });
  }
}

const verdict = {
  schemaVersion: 2,
  runId: scope.runId,
  generatedAt: new Date().toISOString(),
  editor: 'main-session',
  coverage,
  findings,
  relations,
  deferred,
  dropped: [],
};

fs.writeFileSync(path.join(runDir, 'verdict.json'), JSON.stringify(verdict, null, 2) + '\n', 'utf8');
console.log('findings', findings.length, 'relations', relations.length, 'deferred', deferred.length);
console.log('schemaVersion from bundle', bundle.schemaVersion);
console.log('bySeverity', JSON.stringify(findings.reduce((a, f) => ((a[f.severity] = (a[f.severity] || 0) + 1), a), {})));
console.log('byKind', JSON.stringify(findings.reduce((a, f) => ((a[f.kind] = (a[f.kind] || 0) + 1), a), {})));
