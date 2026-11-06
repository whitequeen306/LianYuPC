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
  // 中枢用近白的暖金：和 FACT 扇区的琥珀金拉开距离，否则「中枢」会被误读成一片事实记忆
  core: '#fff1c9',
  relation: '#6fdbc0',
  limbic: '#ff8fab',
  introspect: '#7ed7e8',
  output: '#e6eefc'
}

/**
 * 脑区几何：c 是 3D 中心，r 是三个方向的可视半径，z 越大越靠近观察者。
 * 排布按「前—后」语义摆放：对话从前方左下进入，写入在海马体（底中），
 * 记忆库居中，检索中枢在前方正中，表达在顶部，日记沉淀在后方右下。
 */
export const MEMORY_REGIONS = [
  {
    id: 'input',
    nameKey: 'memory.brain.regions.input.name',
    descKey: 'memory.brain.regions.input.desc',
    unitKey: 'memory.brain.units.messages',
    color: BRAIN_PALETTE.input,
    c: [-148, -26, 112],
    r: [46, 38, 36]
  },
  {
    id: 'hippo',
    nameKey: 'memory.brain.regions.hippo.name',
    descKey: 'memory.brain.regions.hippo.desc',
    unitKey: 'memory.brain.units.written',
    color: BRAIN_PALETTE.hippo,
    c: [-18, -96, 8],
    r: [44, 34, 32]
  },
  {
    id: 'ltm',
    nameKey: 'memory.brain.regions.ltm.name',
    descKey: 'memory.brain.regions.ltm.desc',
    unitKey: 'memory.brain.units.memories',
    color: BRAIN_PALETTE.ltm,
    c: [0, 8, -58],
    r: [76, 62, 54]
  },
  {
    id: 'core',
    nameKey: 'memory.brain.regions.core.name',
    descKey: 'memory.brain.regions.core.desc',
    unitKey: 'memory.brain.units.sectors',
    color: BRAIN_PALETTE.core,
    // 中枢本该在正中央，但那里被长期记忆库的光点占满了，往右上前方挪一点才认得出
    c: [78, 138, 104],
    r: [40, 34, 32]
  },
  {
    id: 'relation',
    nameKey: 'memory.brain.regions.relation.name',
    descKey: 'memory.brain.regions.relation.desc',
    unitKey: 'memory.brain.units.relationMemories',
    color: BRAIN_PALETTE.relation,
    c: [-126, 58, -44],
    r: [42, 34, 32]
  },
  {
    id: 'limbic',
    nameKey: 'memory.brain.regions.limbic.name',
    descKey: 'memory.brain.regions.limbic.desc',
    unitKey: 'memory.brain.units.characters',
    color: BRAIN_PALETTE.limbic,
    c: [136, 46, 58],
    r: [40, 32, 30]
  },
  {
    id: 'introspect',
    nameKey: 'memory.brain.regions.introspect.name',
    descKey: 'memory.brain.regions.introspect.desc',
    unitKey: 'memory.brain.units.diaries',
    color: BRAIN_PALETTE.introspect,
    c: [96, -86, -114],
    r: [40, 32, 30]
  },
  {
    id: 'output',
    nameKey: 'memory.brain.regions.output.name',
    descKey: 'memory.brain.regions.output.desc',
    unitKey: 'memory.brain.units.charactersExpressing',
    color: BRAIN_PALETTE.output,
    c: [-8, 122, 20],
    r: [42, 34, 32]
  }
]

const REGION_BY_ID = new Map(MEMORY_REGIONS.map((r) => [r.id, r]))

/**
 * 标签放在节点上方（-1）还是下方（1）。
 * 靠下的脑区把标签放到下面，避免中间那几个脑区的字叠在一起。
 */
const LABEL_PLACEMENT = {
  input: 1,
  hippo: 1,
  introspect: 1,
  core: 1,
  ltm: -1,
  relation: -1,
  limbic: -1,
  output: -1
}

/**
 * 标签挂在节点右侧（目前只有中枢）：它落在长期记忆库的扇区中间，
 * 上下都会压住光点，只能横向让开。
 */
const LABEL_SIDE = { ltm: 'center' }

/** 脑区之间的数据流；dash 表示「旁路/参考」而不是主链路 */
export const BRAIN_FLOWS = [
  { id: 'input-hippo', from: 'input', to: 'hippo', labelKey: 'memory.brain.flows.wake', dash: false },
  { id: 'hippo-ltm', from: 'hippo', to: 'ltm', labelKey: 'memory.brain.flows.write', dash: false },
  { id: 'ltm-core', from: 'ltm', to: 'core', labelKey: 'memory.brain.flows.index', dash: false },
  { id: 'limbic-core', from: 'limbic', to: 'core', labelKey: 'memory.brain.flows.emotion', dash: false },
  { id: 'relation-core', from: 'relation', to: 'core', labelKey: 'memory.brain.flows.relation', dash: false },
  { id: 'introspect-ltm', from: 'introspect', to: 'ltm', labelKey: 'memory.brain.flows.diary', dash: false },
  { id: 'core-output', from: 'core', to: 'output', labelKey: 'memory.brain.flows.inject', dash: false },
  { id: 'input-core', from: 'input', to: 'core', labelKey: 'memory.brain.flows.topic', dash: true }
]

/** 单次最多画多少颗记忆光点：再多也会糊成一团，还拖慢帧率 */
export const MAX_MEMORY_NODES = 240

const DAY = 86400000
const WEEK = 7 * DAY

/** 确定性伪随机：同一个记忆 id 永远得到同一组抖动，避免每次刷新星图乱跳 */
export function mulberry32(seed) {
  let a = seed >>> 0
  return function next() {
    a = (a + 0x6d2b79f5) >>> 0
    let t = a
    t = Math.imul(t ^ (t >>> 15), t | 1)
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61)
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296
  }
}

function hashString(input) {
  let hash = 2166136261
  for (let i = 0; i < input.length; i += 1) {
    hash ^= input.charCodeAt(i)
    hash = Math.imul(hash, 16777619)
  }
  return hash >>> 0
}

function clamp01(value) {
  if (!Number.isFinite(value)) return 0
  return Math.min(1, Math.max(0, value))
}

/**
 * 时间戳解析统一走 utils/feedTime.parseFeedDateTime：
 * 后端 LocalDateTime 不带时区，业务时区是 Asia/Shanghai（+08:00）。
 * 这里不自己再定一套时区规则，否则星图上的先后顺序会和列表页错开。
 */
export function toTimestamp(value) {
  if (value == null || value === '') return 0
  if (typeof value === 'number') return value < 1e12 ? value * 1000 : value
  if (value instanceof Date) return Number.isNaN(value.getTime()) ? 0 : value.getTime()
  const text = String(value).trim()
  if (!text) return 0
  if (/^\d+$/.test(text)) return toTimestamp(Number(text))
  const parsed = parseFeedDateTime(text)
  return parsed ? parsed.getTime() : 0
}

export function normalizeMemory(raw = {}) {
  const importanceRaw = typeof raw.importance === 'string' ? Number(raw.importance) : raw.importance
  const sourceIds = Array.isArray(raw.sourceMsgIds) ? raw.sourceMsgIds : []
  const type = SECTOR_BY_ID.has(raw.memoryType) ? raw.memoryType : 'FACT'
  const fadeLevel = ['active', 'faded', 'archived', 'dormant'].includes(raw.fadeLevel)
    ? raw.fadeLevel
    : 'active'
  const strengthRaw = typeof raw.strength === 'string' ? Number(raw.strength) : raw.strength
  return {
    id: raw.id,
    characterId: raw.characterId,
    characterName: raw.characterName || '',
    summary: String(raw.summary || '').trim(),
    memoryType: type,
    importance: clamp01(Number(importanceRaw)),
    // 夜间衰减只改 strength/fadeLevel，不删行；星图据此把"想不起来"画暗，而不是画没
    strength: Number.isFinite(Number(strengthRaw)) ? clamp01(Number(strengthRaw)) : 1,
    fadeLevel,
    pinned: Boolean(raw.pinned),
    muted: Boolean(raw.muted),
    layer: raw.layer || 'fact',
    quote: String(raw.quote || '').trim(),
    sourceCount: sourceIds.length,
    createdAt: raw.createdAt,
    at: toTimestamp(raw.createdAt)
  }
}

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
  const list = (Array.isArray(memories) ? memories : [])
    .filter((m) => m && m.id != null)
    .map(normalizeMemory)
    .sort((a, b) => a.at - b.at || Number(a.id) - Number(b.id))

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
  const nodesBySector = { FACT: [], EMOTION: [], RELATION: [], RITUAL: [] }
  for (const memory of drawnList) {
    nodesBySector[memory.memoryType].push(memory)
  }

  const ltm = REGION_BY_ID.get('ltm')
  // 长期记忆库不是一个点，是四瓣：每类记忆占一瓣，绕中枢排开。
  // 之前所有光点都从圆心按同一角度往外长，四团自然叠在一起。
  const SECTOR_ORBIT = 88
  const SECTOR_SPREAD = 34
  const lobeOf = (angleDeg) => {
    const rad = (angleDeg * Math.PI) / 180
    return [
      ltm.c[0] + Math.cos(rad) * SECTOR_ORBIT,
      ltm.c[1] + Math.sin(rad) * SECTOR_ORBIT * 0.84,
      ltm.c[2] + 12
    ]
  }

  const anchors = MEMORY_SECTORS.map((sector) => {
    const rad = (sector.angle * Math.PI) / 180
    const orbit = SECTOR_ORBIT + SECTOR_SPREAD * 1.04 + 8
    return {
      id: `anchor-${sector.id}`,
      sector: sector.id,
      color: sector.color,
      nameKey: sector.nameKey,
      count: bySector[sector.id],
      pos: [
        ltm.c[0] + Math.cos(rad) * orbit,
        ltm.c[1] + Math.sin(rad) * orbit * 0.84,
        ltm.c[2] + 16
      ]
    }
  })

  const memoryNodes = []
  for (const sector of MEMORY_SECTORS) {
    const bucket = nodesBySector[sector.id]
    const lobe = lobeOf(sector.angle)
    const total = bucket.length || 1
    bucket.forEach((memory, index) => {
      // 光点铺满自己那一瓣：半径按 √(i/n) 摊匀（面积均匀），角度整圈随机，
      // 这样每个扇区是一团独立的星团，而不是四团挤在圆心互相盖住。
      const rng = mulberry32(hashString(`${memory.id}:${memory.memoryType}`))
      const spread = SECTOR_SPREAD * Math.sqrt((index + 0.6) / total)
      const theta = rng() * Math.PI * 2
      const jitter = 0.88 + rng() * 0.24
      memoryNodes.push({
        id: `mem-${memory.id}`,
        memoryId: memory.id,
        sector: sector.id,
        color: sector.color,
        title: memory.summary,
        characterId: memory.characterId,
        characterName: nameById.get(String(memory.characterId)) || memory.characterName,
        importance: memory.importance,
        sourceCount: memory.sourceCount,
        at: memory.at,
        createdAt: memory.createdAt,
        strength: memory.strength,
        fadeLevel: memory.fadeLevel,
        pinned: memory.pinned,
        layer: memory.layer,
        // 半径方向压扁、z 方向拉开：转动时四瓣会前后错开，不再是平面色块
        pos: [
          lobe[0] + Math.cos(theta) * spread * jitter * 1.06,
          lobe[1] + Math.sin(theta) * spread * jitter * 0.8,
          lobe[2] + (rng() - 0.5) * 56
        ],
        // 强度不决定"有没有"，只决定"亮不亮"：衰减到 0.15 仍可被搜到
        size: (1.8 + memory.importance * 3) * (0.62 + 0.38 * memory.strength)
      })
    })
  }

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
  const relationTop = [...nodesBySector.RELATION].sort((a, b) => b.importance - a.importance)

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
    labelDir: LABEL_PLACEMENT[region.id] || -1,
    labelSide: LABEL_SIDE[region.id] || null,
    count: counts[region.id] || 0,
    // 有数据才点亮；否则画成暗星云，视觉上「这里还没通电」
    lit: (counts[region.id] || 0) > 0,
    items: itemsByRegion[region.id] || []
  }))

  return {
    regions,
    regionMap: new Map(regions.map((r) => [r.id, r])),
    anchors,
    memoryNodes,
    flows: BRAIN_FLOWS.map((flow) => ({ ...flow })),
    stats: {
      total: list.length,
      characters: charactersWithMemory,
      weekAdded,
      sourceCount,
      sectorsPresent,
      avgImportance,
      latestAt: list.length ? list[list.length - 1].at : 0,
      bySector,
      diaries: diaryList.length,
      states: stateList.length,
      truncated: list.length > MAX_MEMORY_NODES
    }
  }
}

/* ------------------------------------------------------------------ */
/* 召回演示：关键词打分（本地演示，不冒充 Milvus 语义检索）              */
/* ------------------------------------------------------------------ */

function tokenize(query) {
  const normalized = String(query || '').toLowerCase().trim()
  if (!normalized) return []
  const tokens = new Set()
  for (const word of normalized.match(/[a-z0-9]{2,}/g) || []) tokens.add(word)
  const cjk = normalized.replace(/[^\u4e00-\u9fff]/g, '')
  for (let i = 0; i < cjk.length - 1; i += 1) tokens.add(cjk.slice(i, i + 2))
  if (!tokens.size) tokens.add(normalized)
  return [...tokens]
}

/**
 * 关键词召回打分。
 *
 * 线上真正的召回是 Milvus 向量检索 + importance 加权（backend-agent 侧），
 * 前端拿不到向量、也不该假装拿得到。所以这里只做「关键词命中 × 权重 × 新鲜度」
 * 的本地演示，面板上必须标明这是演示，别让人误以为改这里会影响真实召回。
 */
export function scoreRecall(memories = [], query = '', { now = Date.now(), limit = 12 } = {}) {
  const tokens = tokenize(query)
  if (!tokens.length) return []
  const rows = []
  for (const raw of Array.isArray(memories) ? memories : []) {
    const memory = normalizeMemory(raw)
    if (!memory.summary) continue
    const haystack = memory.summary.toLowerCase()
    const hits = tokens.filter((token) => haystack.includes(token)).length
    if (!hits) continue
    const keyword = hits / tokens.length
    const importance = memory.importance
    const age = memory.at ? now - memory.at : 365 * DAY
    const recency = 1 - clamp01(age / (90 * DAY))
    const score = clamp01(keyword * 0.62 + importance * 0.26 + recency * 0.12)
    rows.push({ memory, hits, total: tokens.length, keyword, importance, recency, score })
  }
  rows.sort((a, b) => b.score - a.score || b.memory.at - a.memory.at)
  return rows.slice(0, limit)
}

/* ------------------------------------------------------------------ */
/* 时间轴：最近 N 天的写入节奏                                          */
/* ------------------------------------------------------------------ */

function dayKeyOf(timestamp) {
  const date = new Date(timestamp)
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  return `${date.getFullYear()}-${month}-${day}`
}

export function buildTimeline(memories = [], { days = 14, now = Date.now() } = {}) {
  const span = Math.max(1, days)
  const today = new Date(now)
  today.setHours(0, 0, 0, 0)
  const buckets = new Map()
  for (let i = span - 1; i >= 0; i -= 1) {
    const stamp = today.getTime() - i * DAY
    buckets.set(dayKeyOf(stamp), { key: dayKeyOf(stamp), at: stamp, count: 0, ids: [] })
  }
  for (const raw of Array.isArray(memories) ? memories : []) {
    const memory = normalizeMemory(raw)
    const bucket = buckets.get(dayKeyOf(memory.at || now))
    if (!bucket) continue
    bucket.count += 1
    bucket.ids.push(memory.id)
  }
  const list = [...buckets.values()]
  let running = 0
  for (const bucket of list) {
    running += bucket.count
    bucket.cumulative = running
  }
  return list
}

/* 供组件做「扇区 → 中文名」兜底（i18n 缺失时至少不显示裸 key） */
export function sectorMeta(id) {
  return SECTOR_BY_ID.get(id) || MEMORY_SECTORS[0]
}
