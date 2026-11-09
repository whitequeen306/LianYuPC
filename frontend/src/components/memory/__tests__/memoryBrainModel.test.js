import { describe, expect, it } from 'vitest'
import {
  BRAIN_FLOWS,
  MAX_MEMORY_NODES,
  MEMORY_REGIONS,
  MEMORY_SECTORS,
  buildBrainGraph,
  buildTimeline,
  mulberry32,
  normalizeMemory,
  scoreRecall,
  toTimestamp
} from '@/components/memory/memoryBrainModel'

const NOW = new Date('2026-11-05T12:00:00+08:00').getTime()
const DAY = 86400000

function memory(id, overrides = {}) {
  return {
    id,
    characterId: 1,
    characterName: '林晚',
    summary: `记忆 ${id}`,
    memoryType: 'FACT',
    importance: 0.6,
    sourceMsgIds: [id * 2, id * 2 + 1],
    createdAt: new Date(NOW - id * 3600000).toISOString(),
    ...overrides
  }
}

describe('MEMORY_SECTORS / MEMORY_REGIONS / BRAIN_FLOWS 的一致性', () => {
  it('四个扇区 id 与后端 MemoryType 完全对应', () => {
    expect(MEMORY_SECTORS.map((sector) => sector.id)).toEqual(['FACT', 'EMOTION', 'RELATION', 'RITUAL'])
  })

  it('每个脑区的 nameKey / descKey 都指向 memory.brain 命名空间', () => {
    for (const region of MEMORY_REGIONS) {
      expect(region.nameKey).toBe(`memory.brain.regions.${region.id}.name`)
      expect(region.descKey).toBe(`memory.brain.regions.${region.id}.desc`)
    }
  })

  it('连线两端的脑区都真实存在，不会画出悬空曲线', () => {
    const ids = new Set(MEMORY_REGIONS.map((region) => region.id))
    for (const flow of BRAIN_FLOWS) {
      expect(ids.has(flow.from)).toBe(true)
      expect(ids.has(flow.to)).toBe(true)
      expect(flow.labelKey.startsWith('memory.brain.flows.')).toBe(true)
    }
  })
})

describe('normalizeMemory', () => {
  it('未知 memoryType 回落到 FACT，字符串权重也能用', () => {
    const normalized = normalizeMemory({ id: '7', summary: ' x ', memoryType: 'NOPE', importance: '0.62' })
    expect(normalized.memoryType).toBe('FACT')
    expect(normalized.importance).toBeCloseTo(0.62)
    expect(normalized.summary).toBe('x')
    expect(Number.isFinite(normalized.at)).toBe(true)
  })

  it('衰减字段：缺省按 active/1，未知档位回落 active，强度夹到 0..1', () => {
    const fallback = normalizeMemory({ id: '8', summary: '没写衰减' })
    expect(fallback.fadeLevel).toBe('active')
    expect(fallback.strength).toBe(1)

    const archived = normalizeMemory({ id: '9', summary: '旧事', fadeLevel: 'archived', strength: '0.22' })
    expect(archived.fadeLevel).toBe('archived')
    expect(archived.strength).toBeCloseTo(0.22)

    expect(normalizeMemory({ id: '10', summary: 'x', fadeLevel: 'gone' }).fadeLevel).toBe('active')
    expect(normalizeMemory({ id: '11', summary: 'x', strength: '3' }).strength).toBe(1)
  })

  it('强度只影响亮度：衰减的记忆节点更小，但仍在图里', () => {
    const { memoryNodes } = buildBrainGraph({
      memories: [memory(1, { importance: 0.8 }), memory(2, { importance: 0.8, strength: 0.2, fadeLevel: 'archived' })],
      now: NOW
    })
    const fresh = memoryNodes.find((node) => node.memoryId === 1)
    const old = memoryNodes.find((node) => node.memoryId === 2)
    expect(old.size).toBeLessThan(fresh.size)
    expect(old.size).toBeGreaterThan(0)
  })

  it('权重越界会被夹到 0..1，来源消息数量从数组长度算出并保留可连线 id', () => {
    expect(normalizeMemory({ id: 1, importance: 3 }).importance).toBe(1)
    expect(normalizeMemory({ id: 2, importance: -1 }).importance).toBe(0)
    const normalized = normalizeMemory({ id: 3, sourceMsgIds: [1, 2, 3, null, ' '] })
    expect(normalized.sourceCount).toBe(3)
    expect(normalized.sourceMsgIds).toEqual(['1', '2', '3'])
  })

  it('无时区的 createdAt 按业务时区 Asia/Shanghai 解释', () => {
    // 同一句「11 月 1 日 10:00」不带时区时，必须比 UTC 解释晚 8 小时
    expect(toTimestamp('2026-11-01T10:00:00')).toBe(Date.parse('2026-11-01T10:00:00+08:00'))
  })
})

describe('buildBrainGraph', () => {
  const memories = [
    memory(1, { memoryType: 'FACT', importance: 0.9 }),
    memory(2, { memoryType: 'EMOTION', importance: 0.4 }),
    memory(3, { memoryType: 'RELATION', importance: 0.7 }),
    memory(4, { memoryType: 'RITUAL', importance: 0.5 }),
    // 十天前写入：算总数，不算「本周写入」
    memory(5, { memoryType: 'FACT', importance: 0.3, createdAt: new Date(NOW - 10 * DAY).toISOString() })
  ]

  it('计数按真实链路映射，而不是把总数复制到每个脑区', () => {
    const graph = buildBrainGraph({ memories, now: NOW })
    expect(graph.stats.total).toBe(5)
    expect(graph.stats.bySector).toEqual({ FACT: 2, EMOTION: 1, RELATION: 1, RITUAL: 1 })
    expect(graph.regionMap.get('ltm').count).toBe(5)
    expect(graph.regionMap.get('hippo').count).toBe(4)
    // 五个来源 id 两两成对
    expect(graph.regionMap.get('input').count).toBe(10)
    expect(graph.regionMap.get('core').count).toBe(4)
    expect(graph.regionMap.get('relation').count).toBe(1)
  })

  it('没有数据的脑区不点亮，图例才不会骗人', () => {
    const graph = buildBrainGraph({ memories: [memory(1)], now: NOW })
    expect(graph.regionMap.get('ltm').lit).toBe(true)
    expect(graph.regionMap.get('limbic').count).toBe(0)
    expect(graph.regionMap.get('limbic').lit).toBe(false)
  })

  it('同一份输入两次布局完全一致（刷新不会乱跳）', () => {
    const first = buildBrainGraph({ memories, now: NOW }).memoryNodes.map((node) => node.pos)
    const second = buildBrainGraph({ memories, now: NOW }).memoryNodes.map((node) => node.pos)
    expect(second).toEqual(first)
  })

  it('核心/钉住记忆归到中心，其余按真实类型组成外围簇（角色隔离后不再按角色拆）', () => {
    const graph = buildBrainGraph({
      memories: [
        memory(1, { layer: 'core', memoryType: 'RELATION' }),
        memory(2, { characterId: 2, characterName: '苏', pinned: true }),
        memory(3, { memoryType: 'FACT' }),
        memory(4, { characterId: 2, characterName: '苏', memoryType: 'FACT' }),
        memory(5, { memoryType: 'EMOTION' })
      ],
      characters: [{ id: 1, name: '林晚' }, { id: 2, name: '苏' }],
      now: NOW
    })
    const center = graph.memoryNodes.filter((node) => node.memoryId === 1 || node.memoryId === 2)
    expect(center.every((node) => node.clusterId === 'cluster-core')).toBe(true)
    expect(center.find((node) => node.memoryId === 1).sector).toBe('RELATION')
    // 角色隔离后：外围簇只按类型拆，不再按角色拆
    expect(graph.clusters.filter((cluster) => !cluster.central)).toHaveLength(2)
    expect(graph.clusters.some((cluster) => cluster.sector === 'FACT' && !cluster.central)).toBe(true)
    expect(graph.clusters.some((cluster) => cluster.sector === 'EMOTION' && !cluster.central)).toBe(true)
    // RELATION 的 core 记忆进了中枢，不再单独占簇
    expect(graph.clusters.some((cluster) => cluster.sector === 'RELATION' && !cluster.central)).toBe(false)
    // 类型枢纽：FACT 与 EMOTION 各一个，RELATION 因为只进了中枢，不占枢纽
    expect(graph.sectorHubs.map((sectorHub) => sectorHub.sector)).toEqual(['FACT', 'EMOTION'])
    expect(graph.sectorHubs.find((sectorHub) => sectorHub.sector === 'FACT').clusterIds).toHaveLength(1)
  })

  it('没有 core/pinned 时把少量高重要度记忆放进中心云但不改 layer', () => {
    const graph = buildBrainGraph({
      memories: [
        memory(1, { importance: 0.95 }),
        memory(2, { importance: 0.85 }),
        memory(3, { importance: 0.75 }),
        memory(4, { importance: 0.65 }),
        memory(5, { importance: 0.55 })
      ],
      now: NOW
    })
    const central = graph.memoryNodes.filter((node) => node.clusterId === 'cluster-core')
    expect(central).toHaveLength(4)
    expect(central[0].importance).toBe(0.95)
    expect(central.every((node) => node.layer === 'fact')).toBe(true)
  })

  it('只有共享来源消息 id 的记忆才建立关联边', () => {
    const graph = buildBrainGraph({
      memories: [
        memory(1, { sourceMsgIds: ['shared'] }),
        memory(2, { memoryType: 'EMOTION', sourceMsgIds: ['shared'] }),
        memory(3, { memoryType: 'RELATION', sourceMsgIds: ['other'] })
      ],
      now: NOW
    })
    expect(graph.relatedEdges).toHaveLength(1)
    expect(new Set([graph.relatedEdges[0].fromId, graph.relatedEdges[0].toId])).toEqual(new Set([1, 2]))
  })

  it('超过上限只画权重最高的一批，统计仍然是全量', () => {
    const many = Array.from({ length: MAX_MEMORY_NODES + 60 }, (_, index) =>
      memory(index + 1, { importance: (index % 10) / 10 })
    )
    const graph = buildBrainGraph({ memories: many, now: NOW })
    expect(graph.memoryNodes).toHaveLength(MAX_MEMORY_NODES)
    expect(graph.stats.truncated).toBe(true)
    expect(graph.stats.total).toBe(MAX_MEMORY_NODES + 60)
    expect(graph.regionMap.get('ltm').count).toBe(MAX_MEMORY_NODES + 60)
    // 被丢掉的都是低权重的：留下的最低权重不会低于丢弃的最高权重
    const keptMin = Math.min(...graph.memoryNodes.map((node) => node.importance))
    const keptIds = new Set(graph.memoryNodes.map((node) => node.memoryId))
    const dropped = many.filter((item) => !keptIds.has(item.id))
    expect(keptMin).toBeGreaterThanOrEqual(Math.max(...dropped.map((item) => item.importance)))
  })

  it('没有记忆时也能返回可渲染的空图', () => {
    const graph = buildBrainGraph({ memories: [], characters: [], states: [], diaries: [], now: NOW })
    expect(graph.memoryNodes).toHaveLength(0)
    expect(graph.stats.total).toBe(0)
    expect(graph.regions).toHaveLength(MEMORY_REGIONS.length)
    expect(graph.flows.length).toBe(BRAIN_FLOWS.length)
  })
})

describe('scoreRecall', () => {
  const library = [
    memory(1, { summary: '你喝咖啡只加半包糖', importance: 0.9, createdAt: new Date(NOW - DAY).toISOString() }),
    memory(2, { summary: '你生日是十一月十九', importance: 0.5 }),
    memory(3, { summary: '咖啡因让你睡不着，但你还是喝', importance: 0.2 })
  ]

  it('命中的排前面，分数落在 0..1', () => {
    const rows = scoreRecall(library, '咖啡', { now: NOW })
    expect(rows).toHaveLength(2)
    expect(rows[0].memory.id).toBe(1)
    for (const row of rows) {
      expect(row.score).toBeGreaterThan(0)
      expect(row.score).toBeLessThanOrEqual(1)
    }
  })

  it('空查询直接返回空，不把全库当成结果', () => {
    expect(scoreRecall(library, '   ', { now: NOW })).toEqual([])
    expect(scoreRecall(library, '', { now: NOW })).toEqual([])
  })

  it('中文按双字切词，能命中两字以上的词', () => {
    const rows = scoreRecall(library, '十一月十九', { now: NOW })
    expect(rows.map((row) => row.memory.id)).toEqual([2])
  })

  it('limit 生效', () => {
    expect(scoreRecall(library, '咖啡', { now: NOW, limit: 1 })).toHaveLength(1)
  })
})

describe('buildTimeline', () => {
  it('默认 14 个桶，最后一天是今天，累计单调不减', () => {
    const memories = [
      memory(1, { createdAt: new Date(NOW).toISOString() }),
      memory(2, { createdAt: new Date(NOW - 2 * DAY).toISOString() }),
      memory(3, { createdAt: new Date(NOW - 40 * DAY).toISOString() })
    ]
    const timeline = buildTimeline(memories, { now: NOW })
    expect(timeline).toHaveLength(14)
    expect(timeline[13].count).toBe(1)
    // 40 天前的记忆落在窗口外
    expect(timeline.reduce((sum, bucket) => sum + bucket.count, 0)).toBe(2)
    expect(timeline[13].cumulative).toBe(2)
    for (let i = 1; i < timeline.length; i += 1) {
      expect(timeline[i].cumulative).toBeGreaterThanOrEqual(timeline[i - 1].cumulative)
    }
  })
})

describe('mulberry32', () => {
  it('同种子同序列，不同种子不同序列', () => {
    const a = mulberry32(42)
    const b = mulberry32(42)
    const c = mulberry32(43)
    expect([a(), a(), a()]).toEqual([b(), b(), b()])
    expect(mulberry32(42)()).not.toBe(c())
  })
})

describe('钉住与静音', () => {
  it('节点带上 pinned / muted，静音的记忆仍然留在图里', () => {
    const graph = buildBrainGraph({
      memories: [
        memory(1, { pinned: true }),
        memory(2, { muted: true, importance: 0.9 })
      ],
      now: NOW
    })

    const pinned = graph.memoryNodes.find((node) => node.memoryId === 1)
    const muted = graph.memoryNodes.find((node) => node.memoryId === 2)

    expect(pinned.pinned).toBe(true)
    expect(pinned.muted).toBe(false)
    expect(muted.muted).toBe(true)
    expect(muted.pos).toHaveLength(3)
  })
})
