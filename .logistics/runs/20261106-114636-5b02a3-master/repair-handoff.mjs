import fs from 'node:fs';

const base = 'C:/Users/hp/Desktop/LianYu-PC/.logistics/runs/20261106-114636-5b02a3-master/handoff';

function load(name) {
  return JSON.parse(fs.readFileSync(`${base}/${name}/${name}.json`, 'utf8'));
}
function save(name, h) {
  fs.writeFileSync(`${base}/${name}/${name}.json`, JSON.stringify(h, null, 2) + '\n', 'utf8');
}
function intSections(h) {
  const out = {};
  for (const [k, v] of Object.entries(h.sections || {})) {
    out[k] = typeof v === 'number' ? v : Number(v?.count ?? 0);
  }
  h.sections = out;
}

const correctness = load('correctness');
intSections(correctness);
save('correctness', correctness);

const reliability = load('reliability');
intSections(reliability);
reliability.deferred = [
  {
    from: 'reliability',
    expert: 'correctness',
    file: 'backend-agent/app/memory/relay.py',
    lines: [41],
    summary: 'memory_outbox 认领后卡在 RUNNING、requeue_stuck 无定时调用。本轮已作为 C-006 报告故不重复计数；其“失败不可见”的一面已在 REL-03/REL-06 的监控建议中覆盖。',
  },
  {
    from: 'reliability',
    expert: 'maintainability',
    file: 'backend/lianyu-service/src/main/java/com/lianyu/service/ai/AiResilience.java',
    lines: [1],
    summary: 'AiResilience / UpstreamCircuitBreakerFactory 与 application.yml 的 resilience4j 配置没有任何注入点，属死代码 + 失效配置；Python 侧已有等价熔断，不构成本轮可证的可靠性损失，建议交 maintainability 处理。',
  },
  {
    from: 'reliability',
    expert: 'maintainability',
    file: 'frontend/src/composables/useVoiceRecorder.js',
    lines: [1],
    summary: 'onChunk 失败被 `.catch(() => {})` 吞掉；全仓只有它自己的单测引用该 composable，生产代码未使用，证据不足以证明可达。',
  },
  {
    from: 'reliability',
    expert: 'reliability',
    file: 'frontend/electron',
    lines: [],
    summary: 'updater/qqBridge/napcat 空 catch 扫描命中 31 处，逐处核对均为单条音频/代理探测/状态同步的可降级分支，上层有状态机与日志兜底，不构成失败不可见。',
  },
];
save('reliability', reliability);

const maintainability = load('maintainability');
intSections(maintainability);
maintainability.deferred = [
  {
    from: 'maintainability',
    expert: 'perf',
    file: 'backend-agent/app/main.py',
    lines: [1],
    summary: '单文件 1278 行、25 个路由未拆 router；已作为 M03 上报，若拆分排期靠后可作为独立重构任务跟踪。',
  },
  {
    from: 'maintainability',
    expert: 'correctness',
    file: 'frontend/electron/main.js',
    lines: [2703],
    summary: '孤儿 IPC handler desktop:notify-window-changed：68 个 ipcMain.handle 与 66 个 preload invoke 全部匹配，仅此通道无暴露/无调用；删除属机械改动，价值低于已上报的 12 条。',
  },
  {
    from: 'maintainability',
    expert: 'correctness',
    file: 'AGENTS.md',
    lines: [139],
    summary: '文档与配置漂移：AGENTS.md:139 仍写“当前最新 V55”（实际 V57）；docker-compose.yml:110,157 传的 LIANYU_AGENT_SHADOW_MODE 与 :59 的 LIANYU_MEMORY_OUTBOX_ENABLED 在 backend 无任何读取。',
  },
  {
    from: 'maintainability',
    expert: 'correctness',
    file: 'admin/src/pages/ManagementPage.vue',
    lines: [1],
    summary: 'configs.users.columns 先定义后被整段覆盖，前一处为死赋值；admin 后台为壳子状态，等该页真正开发时一并整理。',
  },
  {
    from: 'maintainability',
    expert: 'perf',
    file: 'frontend/electron/startupProfiler.js',
    lines: [1],
    summary: 'electron/startupProfiler.js 11 行与 src/utils/startupProfiler.js 18 行同签名重复（时钟基准不同）；合并需统一时钟语义，暂列观察。',
  },
];
save('maintainability', maintainability);

console.log('repaired', Object.keys(correctness.sections), Object.keys(reliability.sections), Object.keys(maintainability.sections));
