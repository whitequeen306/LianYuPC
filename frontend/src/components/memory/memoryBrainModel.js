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
  const sourceIds = Array.isArray(raw.sourceMsgIds)
    ? [...new Set(raw.sourceMsgIds.filter((id) => id != null && String(id).trim()).map((id) => String(id)))]
    : []
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
    // 来源 id 是可验证的关系边：共享同一段原始对话的记忆，才允许彼此连线。
    sourceMsgIds: sourceIds,
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
  const characterIds = [...new Set(drawnList.map((memory) => String(memory.characterId ?? 'unknown')))]
  const splitCharacters = characterIds.length > 1 && characterIds.length <= 8
  const characterOrder = new Map(
    characterIds
      .map((id) => ({ id, count: drawnList.filter((memory) => String(memory.characterId ?? 'unknown') === id).length }))
      .sort((a, b) => b.count - a.count || a.id.localeCompare(b.id))
      .map((entry, index) => [entry.id, index])
  )
  const grouped = new Map()
  const centralMemories = drawnList.filter((memory) => memory.layer === 'core' || memory.pinned)
  const centralIds = new Set(centralMemories.map((memory) => String(memory.id)))
  // 没有 core/pinned 时，用少量高重要度记忆组成视觉中心云；不改写它们真实的 layer。
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

  for (const memory of drawnList) {
    // core/pinned 与无明确中枢时的少量高权重候选留在中心；其余按真实类型和角色分簇。
    if (centralIds.has(String(memory.id))) continue
    const characterId = String(memory.characterId ?? 'unknown')
    const key = splitCharacters ? `${memory.memoryType}:${characterId}` : memory.memoryType
    if (!grouped.has(key)) grouped.set(key, [])
    grouped.get(key).push(memory)
  }

  const memoryNodes = []
  const clusters = []
  const anchors = []
  const GOLDEN_ANGLE = Math.PI * (3 - Math.sqrt(5))

  const appendCluster = ({ id, sector, characterId = null, members, pos, central = false }) => {
    const sectorMeta = SECTOR_BY_ID.get(sector)
    const color = central ? BRAIN_PALETTE.core : sectorMeta.color
    const sortedMembers = [...members].sort(
      (a, b) => b.importance - a.importance || b.at - a.at || String(a.id).localeCompare(String(b.id))
    )
    const count = sortedMembers.length
    const spread = central ? Math.min(35, 12 + Math.sqrt(count) * 3.8) : Math.min(54, 17 + Math.sqrt(count) * 3.6)
    const cluster = {
      id,
      sector,
      sectorHubId: central ? null : `sector-hub-${sector}`,
      characterId,
      characterName: characterId == null ? '' : nameById.get(String(characterId)) || '',
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
      // 重要记忆更靠近所属簇的核心，衰减记忆自然落在星团外缘。
      const radius = ring * (0.66 + (1 - memory.importance) * 0.34) * (0.9 + rng() * 0.2)
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
        pos: [
          pos[0] + Math.cos(theta) * radius,
          pos[1] + Math.sin(theta) * radius * 0.78,
          pos[2] + (rng() - 0.5) * (central ? 36 : 56)
        ],
        // 重要度拉开视觉层次；钉住的记忆是簇里的亮星，衰减仍保留可见。
        size: (1.2 + memory.importance * 2.8) * (0.62 + 0.38 * memory.strength) * (memory.pinned ? 1.35 : 1)
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

  if (centralMemories.length) {
    appendCluster({
      id: 'cluster-core',
      sector: 'FACT',
      members: centralMemories,
      pos: [ltm.c[0], ltm.c[1], ltm.c[2] + 14],
      central: true
    })
  }

  for (const sector of MEMORY_SECTORS) {
    const sectorGroups = [...grouped.entries()]
      .filter(([key]) => key.startsWith(`${sector.id}:`) || key === sector.id)
      .map(([key, members]) => ({ key, members }))
      .sort((a, b) => {
        const charA = splitCharacters ? a.key.slice(sector.id.length + 1) : ''
        const charB = splitCharacters ? b.key.slice(sector.id.length + 1) : ''
        return (characterOrder.get(charA) ?? 0) - (characterOrder.get(charB) ?? 0)
      })
    const total = sectorGroups.length
    sectorGroups.forEach(({ key, members }, index) => {
      const characterId = splitCharacters ? key.slice(sector.id.length + 1) : null
      const lane = total > 1 ? index - (total - 1) / 2 : 0
      const laneStep = total > 1 ? Math.min(26, 84 / (total - 1)) : 0
      const angle = (sector.angle + lane * laneStep) * (Math.PI / 180)
      const radius = 150 + Math.min(22, Math.abs(lane) * 6)
      const pos = [
        ltm.c[0] + Math.cos(angle) * radius,
        ltm.c[1] + Math.sin(angle) * radius * 0.84,
        ltm.c[2] + 12 + lane * 5
      ]
      appendCluster({
        id: `cluster-${sector.id}-${characterId ?? 'all'}`,
        sector: sector.id,
        characterId,
        members,
        pos
      })
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

  // 一层语义枢纽把中心与角色簇分开：中心只连到实际出现的 memoryType，
  // 每个类型枢纽再承接同类角色簇，避免多角色时从中心扇出一大片射线。
  const sectorHubs = MEMORY_SECTORS.flatMap((sector) => {
    const sectorClusters = clusters.filter((cluster) => !cluster.central && cluster.sector === sector.id)
    if (!sectorClusters.length) return []
    const total = sectorClusters.reduce((sum, cluster) => sum + cluster.count, 0)
    const centroid = [0, 1, 2].map((axis) =>
      sectorClusters.reduce((sum, cluster) => sum + cluster.pos[axis] * cluster.count, 0) / Math.max(1, total)
    )
    const pos = [
      ltm.c[0] + (centroid[0] - ltm.c[0]) * 0.58,
      ltm.c[1] + (centroid[1] - ltm.c[1]) * 0.58,
      ltm.c[2] + (centroid[2] - ltm.c[2]) * 0.58
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
    sectorHubs,
    clusters,
    memoryNodes,
    relatedEdges,
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
