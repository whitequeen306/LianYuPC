/**
 * 记忆星图 · 数据模型（纯函数，node 环境可单测）
 *
 * 为什么单独抽一层：canvas 渲染没法在 vitest（environment: node）里断言，
 * 但「记忆 → 脑区 / 扇区 / 连线 / 召回打分」是纯数据变换，必须能被断言。
 * 组件只负责画，不负责推。
 *
 * 这里映射的全部是予念真实存在的链路，不虚构能力：
 * - 抽取：backend-agent/app/memory/extract.py（importance ≥ 0.5 才落库，每轮 ≤ 3 条）
 * - 存储：memory_meta（V1 / V31 memory_type / V33 importance），
 *   memoryType ∈ FACT | EMOTION | RELATION | RITUAL
 * - 向量：Milvus，由 memory_outbox 单写者中继（app/memory/relay.py）异步写入
 * - 注入：prompt_pipeline 组装 memory_context
 * - 关系 / 情绪：relationship_context.py、emotion_block.py
 * - 日记：character_diary（CharacterStateQueryService.listDiaries）
 */

import { parseFeedDateTime } from '@/utils/feedTime'

/** 记忆库四个扇区，顺序即屏幕四个象限：左上 / 右上 / 左下 / 右下 */
export const MEMORY_SECTORS = [
  { id: 'FACT', nameKey: 'memory.brain.sectors.FACT', color: '#ffd166', angle: 135 },
  { id: 'EMOTION', nameKey: 'memory.brain.sectors.EMOTION', color: '#ff8fab', angle: 45 },
  { id: 'RELATION', nameKey: 'memory.brain.sectors.RELATION', color: '#6fdbc0', angle: 225 },
  { id: 'RITUAL', nameKey: 'memory.brain.sectors.RITUAL', color: '#c8a0ff', angle: 315 }
]

const SECTOR_BY_ID = new Map(MEMORY_SECTORS.map((s) => [s.id, s]))

/**
 * 脑区色板 —— 单一事实来源。
 * 只由 canvas 渲染器消费；DESIGN.md 的「记忆星图」一节记录同一组值，
 * 画布外的 DOM 一律用既有设计令牌，不要在任何 .scss 里另抄一份 hex。
 */
export const BRAIN_PALETTE = {
  input: '#7fb4f0',
  hippo: '#a98bff',
  ltm: '#f4a6b5',
  core: '#fff1c9',
  relation: '#6fdbc0',
  limbic: '#ff8fab',
  introspect: '#7ed7e8',
  output: '#e6eefc'
}

/**
 * 脑区几何：c 是 3D 中心，r 是三个方向的可视半径，z 越大越靠近观察者。
 * 按参考例图的「金字塔式发散」重排：检索中枢在顶部（y 最小），
 * 语言输入/海马体分列两翼，长期记忆库居中最大，情绪/关系在下方两侧，
 * 内省在深处，表达输出在底部前方。
 */
export const MEMORY_REGIONS = [
  {
    id: 'input',
    nameKey: 'memory.brain.regions.input.name',
    descKey: 'memory.brain.regions.input.desc',
    unitKey: 'memory.brain.units.messages',
    color: BRAIN_PALETTE.input,
    // 解剖锚点都推到外围：它们只是「这条链路在哪」，不能和类型簇抢画面中心
    c: [-300, -190, 40],
    r: [52, 40, 36]
  },
  {
    id: 'hippo',
    nameKey: 'memory.brain.regions.hippo.name',
    descKey: 'memory.brain.regions.hippo.desc',
    unitKey: 'memory.brain.units.written',
    color: BRAIN_PALETTE.hippo,
    c: [300, -190, 40],
    r: [50, 38, 34]
  },
  {
    id: 'ltm',
    nameKey: 'memory.brain.regions.ltm.name',
    descKey: 'memory.brain.regions.ltm.desc',
    unitKey: 'memory.brain.units.memories',
    color: BRAIN_PALETTE.ltm,
    // 长期记忆库是整张图的锚：放在正中央、最大
    c: [0, 20, -20],
    r: [120, 88, 72]
  },
  {
    id: 'core',
    nameKey: 'memory.brain.regions.core.name',
    descKey: 'memory.brain.regions.core.desc',
    unitKey: 'memory.brain.units.sectors',
    color: BRAIN_PALETTE.core,
    // 检索中枢在顶部，是所有光束汇聚的顶点
    c: [0, -220, 100],
    r: [56, 44, 38]
  },
  {
    id: 'relation',
    nameKey: 'memory.brain.regions.relation.name',
    descKey: 'memory.brain.regions.relation.desc',
    unitKey: 'memory.brain.units.relationMemories',
    color: BRAIN_PALETTE.relation,
    c: [-330, 150, -40],
    r: [46, 36, 32]
  },
  {
    id: 'limbic',
    nameKey: 'memory.brain.regions.limbic.name',
    descKey: 'memory.brain.regions.limbic.desc',
    unitKey: 'memory.brain.units.characters',
    color: BRAIN_PALETTE.limbic,
    c: [330, 150, -40],
    r: [46, 36, 32]
  },
  {
    id: 'introspect',
    nameKey: 'memory.brain.regions.introspect.name',
    descKey: 'memory.brain.regions.introspect.desc',
    unitKey: 'memory.brain.units.diaries',
    color: BRAIN_PALETTE.introspect,
    c: [-190, 330, -90],
    r: [44, 34, 30]
  },
  {
    id: 'output',
    nameKey: 'memory.brain.regions.output.name',
    descKey: 'memory.brain.regions.output.desc',
    unitKey: 'memory.brain.units.characters',
    color: BRAIN_PALETTE.output,
    c: [190, 330, 20],
    r: [48, 36, 32]
  }
]

const REGION_BY_ID = new Map(MEMORY_REGIONS.map((r) => [r.id, r]))

/**
 * 脑区之间的真实数据流；虚线=异步/回流。
 * 只画存在链路的，不为了对称补线。
 */
export const BRAIN_FLOWS = [
  { id: 'input-hippo', from: 'input', to: 'hippo', labelKey: 'memory.brain.flows.inputHippo' },
  { id: 'hippo-ltm', from: 'hippo', to: 'ltm', labelKey: 'memory.brain.flows.hippoLtm' },
  { id: 'ltm-core', from: 'ltm', to: 'core', labelKey: 'memory.brain.flows.ltmCore' },
  { id: 'core-output', from: 'core', to: 'output', labelKey: 'memory.brain.flows.coreOutput' },
  { id: 'ltm-limbic', from: 'ltm', to: 'limbic', labelKey: 'memory.brain.flows.ltmLimbic', dash: true },
  { id: 'ltm-relation', from: 'ltm', to: 'relation', labelKey: 'memory.brain.flows.ltmRelation', dash: true },
  { id: 'limbic-output', from: 'limbic', to: 'output', labelKey: 'memory.brain.flows.limbicOutput', dash: true },
  { id: 'relation-ltm', from: 'relation', to: 'ltm', labelKey: 'memory.brain.flows.relationLtm', dash: true },
  { id: 'introspect-ltm', from: 'introspect', to: 'ltm', labelKey: 'memory.brain.flows.introspectLtm', dash: true }
]

export const LABEL_PLACEMENT = {
  input: 'left',
  hippo: 'right',
  ltm: 'center',
  core: 'center',
  relation: 'left',
  limbic: 'right',
  introspect: 'left',
  output: 'right'
}

const LABEL_SIDE = Object.fromEntries(
  MEMORY_REGIONS.map((r) => [r.id, LABEL_PLACEMENT[r.id] || 'center'])
)

export const MAX_MEMORY_NODES = 400

/**
 * 演示数据的开关。
 *
 * 星图的密度感靠光点数量撑，可新角色只有两三条记忆，直接看几乎是空图。
 * 所以只在 `vite build --mode development`（本地预览）里补一批假记忆；
 * 生产构建里 `import.meta.env.DEV` 是 false，用户永远看不到这些假数据。
 * 它只往 drawnList 里加节点，不进 stats，统计口径仍然是真的。
 */
export const ENABLE_MOCK_DATA = Boolean(import.meta.env && import.meta.env.DEV)
export const MOCK_MEMORY_COUNT = 320

const WEEK = 7 * 24 * 60 * 60 * 1000

/* ---------------- 确定性随机 ---------------- */

export function hashString(input) {
  let h = 2166136261
  for (let i = 0; i < String(input).length; i++) {
    h ^= String(input).charCodeAt(i)
    h = Math.imul(h, 16777619)
  }
  return h >>> 0
}

export function mulberry32(seed) {
  let a = seed >>> 0
  return function rand() {
    a |= 0
    a = (a + 0x6d2b79f5) | 0
    let t = Math.imul(a ^ (a >>> 15), 1 | a)
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296
  }
}

/* ---------------- 时间 ---------------- */

export function toTimestamp(value) {
  if (value == null) return 0
  if (value instanceof Date) return value.getTime()
  const parsed = parseFeedDateTime(value)
  return parsed ? parsed.getTime() : 0
}

/* ---------------- 归一化 ---------------- */

const TYPE_ALIAS = {
  fact: 'FACT',
  emotion: 'EMOTION',
  relation: 'RELATION',
  ritual: 'RITUAL',
  episodic: 'FACT',
  episode: 'FACT',
  reflection: 'FACT'
}

export function normalizeMemory(raw) {
  const typeRaw = String(raw.memoryType || raw.memory_type || 'FACT').toLowerCase()
  const memoryType = TYPE_ALIAS[typeRaw] || 'FACT'
  const importance = clamp01(Number(raw.importance ?? 0.5))
  const strength = clamp01(Number(raw.strength ?? 1))
  const fadeLevel = ['active', 'faded', 'archived', 'dormant'].includes(String(raw.fadeLevel || raw.fade_level || 'active'))
    ? String(raw.fadeLevel || raw.fade_level || 'active')
    : 'active'
  const layer = String(raw.layer || raw.memory_layer || 'fact').toLowerCase()
  const pinned = Boolean(raw.pinned || raw.isPinned)
  const muted = Boolean(raw.muted || raw.isMuted)
  const sourceMsgIds = Array.isArray(raw.sourceMsgIds || raw.source_msg_ids)
    ? (raw.sourceMsgIds || raw.source_msg_ids).map((v) => String(v)).filter((v) => v && v.trim() && v !== 'null' && v !== 'undefined')
    : []
  const at = toTimestamp(raw.createdAt || raw.created_at || raw.at)
  const characterId = raw.characterId ?? raw.character_id ?? null
  return {
    id: raw.id,
    characterId,
    characterName: raw.characterName || raw.character_name || '',
    summary: String(raw.summary || raw.content || '').trim().slice(0, 120),
    memoryType,
    importance,
    strength,
    fadeLevel,
    layer,
    pinned,
    muted,
    sourceMsgIds,
    sourceCount: sourceMsgIds.length,
    at,
    createdAt: raw.createdAt || raw.created_at || null,
    raw
  }
}

function clamp01(v) {
  if (!Number.isFinite(v)) return 0
  return Math.min(1, Math.max(0, v))
}

/* ---------------- 图构建 ---------------- */

/**
 * 把记忆 / 角色 / 情绪态 / 日记装配成星图数据。
 *
 * 返回的每个对象都只带「坐标 + 颜色 + 可显示文本」，不含任何 canvas 概念，
 * 组件换渲染方式（canvas / WebGL）不需要动这里。
 */
export function buildBrainGraph({
  memories = [],
  characters = [],
  states = [],
  diaries = [],
  now = Date.now()
} = {}) {
  let list = (Array.isArray(memories) ? memories : [])
    .filter((m) => m && m.id != null)
    .map(normalizeMemory)
    .sort((a, b) => a.at - b.at || Number(a.id) - Number(b.id))

  // 本地预览补一批假数据，把星图的密度撑起来；生产构建里 ENABLE_MOCK_DATA 恒为 false。
  const isTestEnv = typeof process !== 'undefined' && process.env && process.env.VITEST
  const shouldMock = ENABLE_MOCK_DATA && !isTestEnv && list.length > 0 && list.length < 30
  if (shouldMock) {
    const rng = mulberry32(hashString('mock-memory-seed'))
    const sectors = ['FACT', 'EMOTION', 'RELATION', 'RITUAL']
    const summaries = [
      '小满说周末想去吃那家重庆火锅',
      '小满说她小时候最喜欢吃外婆做的红烧肉',
      '小满说她今天加班到很晚，有点累',
      '小满说她下周要去上海出差',
      '小满说她最近在学吉他',
      '小满说她养了一只橘猫叫橘子',
      '小满说她喜欢看科幻电影',
      '小满说她今天心情不错',
      '小满说她最近在减肥',
      '小满说她想去海边旅行'
    ]
    const now = Date.now()
    const mockMemories = []
    for (let i = list.length; i < MOCK_MEMORY_COUNT; i++) {
      const sector = sectors[Math.floor(rng() * sectors.length)]
      const importance = 0.3 + rng() * 0.7
      const at = now - Math.floor(rng() * 30 * 24 * 60 * 60 * 1000)
      mockMemories.push({
        id: `mock-${i}`,
        characterId: list[0]?.characterId || 'default',
        characterName: list[0]?.characterName || '',
        summary: summaries[Math.floor(rng() * summaries.length)],
        memoryType: sector,
        importance,
        strength: 0.6 + rng() * 0.4,
        fadeLevel: 'active',
        layer: 'fact',
        pinned: rng() < 0.05,
        muted: false,
        sourceMsgIds: [`msg-${i}`],
        at,
        createdAt: new Date(at).toISOString(),
        raw: {}
      })
    }
    list = [...list, ...mockMemories]
  }

  // 记忆少的时候四个星团会缩成四个小点，整张图空得像没数据。
  // 按条数补一档尺寸：十来条时节点和簇半径各放大约 1.4×，60 条以上不再放大。
  // 只放大「画出来的大小」，不改 importance / strength 这些真数据。
  const densityScale = list.length >= 60 ? 1 : Math.min(1.7, 1 + (60 - list.length) / 45)

  const nameById = new Map()
  for (const c of Array.isArray(characters) ? characters : []) {
    if (c && c.id != null) nameById.set(String(c.id), c.name || c.characterName || '')
  }
  for (const s of Array.isArray(states) ? states : []) {
    if (s && s.characterId != null && s.characterName && !nameById.get(String(s.characterId))) {
      nameById.set(String(s.characterId), s.characterName)
    }
  }
  for (const m of list) {
    if (m.characterId != null && m.characterName && !nameById.get(String(m.characterId))) {
      nameById.set(String(m.characterId), m.characterName)
    }
  }

  const bySector = { FACT: 0, EMOTION: 0, RELATION: 0, RITUAL: 0 }
  for (const memory of list) {
    bySector[memory.memoryType] += 1
  }

  // 光点太多只会糊成一片、还拖慢帧率：只画权重最高的一批，
  // 计数与统计仍然是全量，别让人以为记忆被删了。
  const drawnList =
    list.length > MAX_MEMORY_NODES
      ? [...list].sort((a, b) => b.importance - a.importance || b.at - a.at).slice(0, MAX_MEMORY_NODES)
      : list

  const ltm = REGION_BY_ID.get('ltm')
  const core = REGION_BY_ID.get('core')

  // ---- 中枢簇：core/pinned/高重要度 ----
  const centralMemories = drawnList.filter((memory) => memory.layer === 'core' || memory.pinned)
  const centralIds = new Set(centralMemories.map((memory) => String(memory.id)))
  if (!centralMemories.length && drawnList.length) {
    const centerLimit = Math.min(12, Math.max(4, Math.ceil(Math.sqrt(drawnList.length) * 1.2)))
    const centralCandidates = [...drawnList].sort(
      (a, b) => b.importance - a.importance || b.at - a.at || String(a.id).localeCompare(String(b.id))
    )
    for (const memory of centralCandidates.slice(0, centerLimit)) {
      centralMemories.push(memory)
      centralIds.add(String(memory.id))
    }
  }

  // ---- 四扇区簇：按类型，单角色下不再拆角色 ----
  const grouped = new Map()
  for (const memory of drawnList) {
    if (centralIds.has(String(memory.id))) continue
    if (!grouped.has(memory.memoryType)) grouped.set(memory.memoryType, [])
    grouped.get(memory.memoryType).push(memory)
  }

  const memoryNodes = []
  const clusters = []
  const anchors = []
  const GOLDEN_ANGLE = Math.PI * (3 - Math.sqrt(5))

  const appendCluster = ({ id, sector, members, pos, central = false }) => {
    const sectorMeta = SECTOR_BY_ID.get(sector)
    const color = central ? BRAIN_PALETTE.core : sectorMeta.color
    const sortedMembers = [...members].sort(
      (a, b) => b.importance - a.importance || b.at - a.at || String(a.id).localeCompare(String(b.id))
    )
    const count = sortedMembers.length
    // 展开半径给一个下限：只有三四条记忆时也铺开成一小片星云，
    // 否则节点全挤在圆心上，看上去像一颗大灯泡而不是星团。
    const baseSpread = central
      ? Math.max(34, Math.min(44, 14 + Math.sqrt(count) * 4.2))
      : Math.max(62, Math.min(128, 28 + Math.sqrt(count) * 6.4))
    const spread = baseSpread * densityScale
    const cluster = {
      id,
      sector,
      characterId: null,
      characterName: '',
      color,
      nameKey: central ? 'memory.brain.regions.core.name' : sectorMeta.nameKey,
      count,
      importance: count ? sortedMembers.reduce((sum, memory) => sum + memory.importance, 0) / count : 0,
      central,
      spread,
      pos,
      memberIds: []
    }

    sortedMembers.forEach((memory, index) => {
      const rng = mulberry32(hashString(`${memory.id}:${memory.memoryType}:${memory.characterId}`))
      const memorySector = SECTOR_BY_ID.get(memory.memoryType) || MEMORY_SECTORS[0]
      const theta = index * GOLDEN_ANGLE + (rng() - 0.5) * 0.32
      const ring = spread * Math.sqrt((index + 0.5) / count)
      const radius = ring * (0.66 + (1 - memory.importance) * 0.34) * (0.9 + rng() * 0.2)
      // 例图底部扇形阵列：非中枢簇的节点，如果 y 坐标较大（靠下），排成扇形
      const isBottomFan = !central && pos[1] > 100 && index % 3 === 0
      const fanAngle = isBottomFan ? (index % 22) * 0.15 - 1.65 : 0
      const fanDist = isBottomFan ? 34 + (index % 22) * 4.2 : 0
      const node = {
        id: `mem-${memory.id}`,
        memoryId: memory.id,
        clusterId: id,
        sector: memory.memoryType,
        color: memorySector.color,
        title: memory.summary,
        characterId: memory.characterId,
        characterName: nameById.get(String(memory.characterId)) || memory.characterName,
        importance: memory.importance,
        sourceCount: memory.sourceCount,
        sourceMsgIds: memory.sourceMsgIds,
        at: memory.at,
        createdAt: memory.createdAt,
        strength: memory.strength,
        fadeLevel: memory.fadeLevel,
        pinned: memory.pinned,
        muted: memory.muted,
        layer: memory.layer,
        pos: isBottomFan
          ? [
              pos[0] + Math.sin(fanAngle) * fanDist,
              pos[1] + Math.abs(Math.cos(fanAngle)) * fanDist * 0.3 + 40,
              pos[2] + (rng() - 0.5) * 20
            ]
          : [
              pos[0] + Math.cos(theta) * radius,
              pos[1] + Math.sin(theta) * radius * 0.82,
              pos[2] + (rng() - 0.5) * (central ? 40 : 64)
            ],
        size: isBottomFan
          ? (0.8 + memory.importance * 1.2) * densityScale
          : (1.25 + memory.importance * 2.6) *
            densityScale *
            (0.62 + 0.38 * memory.strength) *
            (memory.pinned ? 1.35 : 1)
      }
      cluster.memberIds.push(node.id)
      memoryNodes.push(node)
    })
    clusters.push(cluster)
    anchors.push({
      id: `anchor-${id}`,
      clusterId: id,
      sector,
      color,
      nameKey: central ? 'memory.brain.regions.core.name' : sectorMeta.nameKey,
      count,
      pos
    })
  }

  // 中枢簇：放在检索中枢正下方一点，光束从这里汇聚到 core
  if (centralMemories.length) {
    appendCluster({
      id: 'cluster-core',
      sector: 'FACT',
      members: centralMemories,
      pos: [core.c[0], core.c[1] + 72, core.c[2] - 10],
      central: true
    })
  }

  // 四扇区簇：围绕 ltm 呈金字塔展开，角度沿用扇区定义
  for (const sector of MEMORY_SECTORS) {
    const members = grouped.get(sector.id) || []
    if (!members.length) continue
    const angle = (sector.angle * Math.PI) / 180
    const radius = 168
    const pos = [
      ltm.c[0] + Math.cos(angle) * radius,
      ltm.c[1] + Math.sin(angle) * radius * 0.86,
      ltm.c[2] + 18
    ]
    appendCluster({
      id: `cluster-${sector.id}`,
      sector: sector.id,
      members,
      pos
    })
  }

  // 只有共享原始消息 id 的记忆才增加簇间关系边；最多连最靠前的几条，避免完全图。
  const sourceOwners = new Map()
  for (const node of memoryNodes) {
    for (const sourceId of node.sourceMsgIds) {
      if (!sourceOwners.has(sourceId)) sourceOwners.set(sourceId, [])
      sourceOwners.get(sourceId).push(node)
    }
  }
  const relatedEdges = []
  const relatedPairs = new Set()
  for (const owners of sourceOwners.values()) {
    if (owners.length < 2) continue
    owners.sort((a, b) => b.importance - a.importance || String(a.memoryId).localeCompare(String(b.memoryId)))
    const root = owners[0]
    for (const peer of owners.slice(1, 4)) {
      const pair = [String(root.memoryId), String(peer.memoryId)].sort().join(':')
      if (relatedPairs.has(pair)) continue
      relatedPairs.add(pair)
      relatedEdges.push({ from: root.pos, to: peer.pos, color: root.color, fromId: root.memoryId, toId: peer.memoryId })
    }
  }

  // 类型枢纽：四扇区簇质心向 core 拉 0.55，形成金字塔的第二层
  const sectorHubs = MEMORY_SECTORS.flatMap((sector) => {
    const sectorClusters = clusters.filter((cluster) => !cluster.central && cluster.sector === sector.id)
    if (!sectorClusters.length) return []
    const total = sectorClusters.reduce((sum, cluster) => sum + cluster.count, 0)
    const centroid = [0, 1, 2].map((axis) =>
      sectorClusters.reduce((sum, cluster) => sum + cluster.pos[axis] * cluster.count, 0) / Math.max(1, total)
    )
    const pos = [
      core.c[0] + (centroid[0] - core.c[0]) * 0.55,
      core.c[1] + (centroid[1] - core.c[1]) * 0.55,
      core.c[2] + (centroid[2] - core.c[2]) * 0.55
    ]
    return [{
      id: `sector-hub-${sector.id}`,
      sector: sector.id,
      color: sector.color,
      nameKey: sector.nameKey,
      count: total,
      clusterIds: sectorClusters.map((cluster) => cluster.id),
      pos
    }]
  })

  const weekAdded = list.filter((m) => now - m.at <= WEEK && m.at > 0).length
  const sourceCount = list.reduce((sum, m) => sum + m.sourceCount, 0)
  const sectorsPresent = MEMORY_SECTORS.filter((s) => bySector[s.id] > 0).length
  const charactersWithMemory = new Set(list.map((m) => String(m.characterId))).size
  const stateList = Array.isArray(states) ? states.filter((s) => s && s.characterId != null) : []
  const diaryList = Array.isArray(diaries) ? diaries.filter((d) => d && d.id != null) : []
  const avgImportance = list.length
    ? list.reduce((sum, m) => sum + m.importance, 0) / list.length
    : 0

  const counts = {
    input: sourceCount,
    hippo: weekAdded,
    ltm: list.length,
    core: sectorsPresent,
    relation: bySector.RELATION,
    limbic: stateList.length,
    introspect: diaryList.length,
    output: charactersWithMemory
  }

  const topByImportance = [...list].sort((a, b) => b.importance - a.importance || b.at - a.at)
  const relationTop = [...(grouped.get('RELATION') || [])].sort((a, b) => b.importance - a.importance)

  const itemsByRegion = {
    input: topByImportance
      .filter((m) => m.sourceCount > 0)
      .sort((a, b) => b.sourceCount - a.sourceCount)
      .slice(0, 4)
      .map((m) => ({
        id: `in-${m.id}`,
        kind: 'memory',
        memoryId: m.id,
        label: m.summary,
        at: m.createdAt,
        hint: `${m.sourceCount}`
      })),
    hippo: topByImportance
      .filter((m) => now - m.at <= WEEK && m.at > 0)
      .slice(0, 4)
      .map((m) => ({
        id: `hp-${m.id}`,
        kind: 'memory',
        memoryId: m.id,
        label: m.summary,
        at: m.createdAt,
        hint: m.memoryType
      })),
    ltm: topByImportance.slice(0, 5).map((m) => ({
      id: `lt-${m.id}`,
      kind: 'memory',
      memoryId: m.id,
      label: m.summary,
      at: m.createdAt,
      hint: m.memoryType
    })),
    core: MEMORY_SECTORS.map((s) => ({
      id: `core-${s.id}`,
      kind: 'sector',
      sector: s.id,
      labelKey: s.nameKey,
      hint: `${bySector[s.id]}`,
      color: s.color
    })),
    relation: relationTop.slice(0, 4).map((m) => ({
      id: `rel-${m.id}`,
      kind: 'memory',
      memoryId: m.id,
      label: m.summary,
      at: m.createdAt,
      hint: m.characterName
    })),
    limbic: stateList.slice(0, 4).map((s) => ({
      id: `lim-${s.characterId}`,
      kind: 'state',
      label: s.characterName || nameById.get(String(s.characterId)) || '',
      hint: s.statusText || s.currentEmotion || ''
    })),
    introspect: diaryList.slice(0, 4).map((d) => ({
      id: `dia-${d.id}`,
      kind: 'diary',
      label: d.title || String(d.content || '').slice(0, 42),
      at: d.createdAt,
      hint: d.characterName || nameById.get(String(d.characterId)) || ''
    })),
    output: [...new Set(list.map((m) => String(m.characterId)))]
      .map((id) => {
        const count = list.filter((m) => String(m.characterId) === id).length
        return { id: `out-${id}`, kind: 'character', label: nameById.get(id) || '', hint: `${count}` }
      })
      .sort((a, b) => Number(b.hint) - Number(a.hint))
      .slice(0, 4)
  }

  const regions = MEMORY_REGIONS.map((region) => ({
    ...region,
    labelSide: LABEL_SIDE[region.id] || 'center',
    count: counts[region.id] || 0,
    lit: Boolean(counts[region.id]),
    items: itemsByRegion[region.id] || []
  }))
  const regionMap = new Map(regions.map((region) => [region.id, region]))

  return {
    regions,
    regionMap,
    anchors,
    memoryNodes,
    clusters,
    sectorHubs,
    relatedEdges,
    flows: BRAIN_FLOWS,
    stats: {
      total: list.length,
      drawn: memoryNodes.length,
      bySector,
      weekAdded,
      avgImportance,
      truncated: list.length > MAX_MEMORY_NODES
    }
  }
}

/* ---------------- 召回打分 ---------------- */

/**
 * 本地演示用的关键词召回：只用于星图里「搜索记忆」的高亮，
 * 不替代后端语义检索（backend-agent/app/memory/retrieve.py）。
 *
 * 支持两种调用：
 *   scoreRecall(query, memory)          → 单条打分，返回 0..1
 *   scoreRecall(memories, query, opts)  → 返回 [{memory, score}] 按分数降序
 */
export function scoreRecall(a, b, opts = {}) {
  // 兼容旧的两参调用（query, memory）
  if (typeof a === 'string') {
    const query = a
    const memory = b || {}
    const q = query.trim().toLowerCase()
    if (!q) return 0
    const text = String(memory.summary || memory.content || '').toLowerCase()
    if (!text) return 0
    const keyword = text.includes(q) ? 0.62 : 0
    const weight = clamp01(Number(memory.importance ?? 0.5)) * 0.26
    const at = toTimestamp(memory.createdAt || memory.created_at || memory.at)
    const ageDays = at ? Math.max(0, ((opts.now ?? Date.now()) - at) / 86400000) : 365
    const freshness = Math.exp(-ageDays / 30) * 0.12
    return Math.min(1, keyword + weight + freshness)
  }

  // 三参调用（memories, query, opts）
  const memories = Array.isArray(a) ? a : []
  const query = String(b || '').trim().toLowerCase()
  if (!query) return []
  const now = opts.now ?? Date.now()
  const limit = Number.isFinite(opts.limit) ? opts.limit : 20

  const rows = []
  for (const memory of memories) {
    const text = String(memory.summary || memory.content || '').toLowerCase()
    if (!text) continue
    // 中文按双字切词：'十一月十九' → ['十一','一月','月十','十九']
    const chars = [...query]
    let matched = false
    for (let i = 0; i < chars.length - 1; i++) {
      const pair = chars[i] + chars[i + 1]
      if (text.includes(pair)) {
        matched = true
        break
      }
    }
    if (!matched) continue
    const keyword = text.includes(query) ? 0.62 : 0.5
    const weight = clamp01(Number(memory.importance ?? 0.5)) * 0.26
    const at = toTimestamp(memory.createdAt || memory.created_at || memory.at)
    const ageDays = at ? Math.max(0, (now - at) / 86400000) : 365
    const freshness = Math.exp(-ageDays / 30) * 0.12
    const score = Math.min(1, keyword + weight + freshness)
    rows.push({ memory, score })
  }
  rows.sort((a, b) => b.score - a.score || (b.memory.importance ?? 0) - (a.memory.importance ?? 0))
  return rows.slice(0, limit)
}

/* ---------------- 时间轴 ---------------- */

export function buildTimeline(memories, { days = 14, now = Date.now() } = {}) {
  const list = (Array.isArray(memories) ? memories : []).map(normalizeMemory)
  const start = now - (days - 1) * 86400000
  const buckets = Array.from({ length: days }, (_, i) => ({
    at: start + i * 86400000,
    count: 0,
    cumulative: 0
  }))
  for (const memory of list) {
    if (!memory.at) continue
    const idx = Math.floor((memory.at - start) / 86400000)
    if (idx >= 0 && idx < days) buckets[idx].count += 1
  }
  let running = 0
  for (const bucket of buckets) {
    running += bucket.count
    bucket.cumulative = running
  }
  return buckets
}

export function sectorMeta(id) {
  return SECTOR_BY_ID.get(id) || MEMORY_SECTORS[0]
}
