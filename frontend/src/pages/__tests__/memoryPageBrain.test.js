import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'

const currentDir = dirname(fileURLToPath(import.meta.url))
const pageSource = readFileSync(resolve(currentDir, '../MemoryPage.vue'), 'utf8')
const mapSource = readFileSync(resolve(currentDir, '../../components/memory/MemoryBrainMap.vue'), 'utf8')
const modelSource = readFileSync(resolve(currentDir, '../../components/memory/memoryBrainModel.js'), 'utf8')

describe('MemoryPage ↔ 星图集成', () => {
  it('星图是默认视图，列表视图仍然保留', () => {
    expect(pageSource).toContain("ref(readView())")
    expect(pageSource).toContain("const VIEWS = ['brain', 'list', 'episodes']")
    expect(pageSource).toContain('VIEWS.includes(saved)')
    expect(pageSource).toContain('<MemoryBrainMap')
    expect(pageSource).toContain('v-else-if="view === \'brain\'"')
  })

  it('一次拉齐记忆 / 情绪态 / 日记，星图不会先空后跳', () => {
    expect(pageSource).toContain('listMemories(')
    expect(pageSource).toContain('listCharacterStates({ silent: true })')
    expect(pageSource).toContain('listAllDiaries({ page: 1, size: 30 }, { silent: true })')
    expect(pageSource).toContain('await Promise.all(')
  })

  it('星图节点要翻译回记忆 id 再删除，否则会拿 mem-123 去调接口', () => {
    expect(modelSource).toContain("id: `mem-${memory.id}`")
    expect(pageSource).toContain('const nodeLike = mem.memoryId != null')
    expect(pageSource).toContain('const targetId = mem.memoryId ?? mem.id')
    expect(pageSource).toContain('await deleteMemory(targetId)')
  })

  it('钉住也走节点→记忆 id 的翻译，且本地按后端口径先改', () => {
    expect(pageSource).toContain('setMemoryPinned(targetId, next)')
    expect(pageSource).toContain('const patch = next')
    expect(pageSource).toContain("{ pinned: true, strength: 1, fadeLevel: 'active', status: 'active' }")
  })

  it('选中态统一用记忆 id，星图才能高亮对应光点', () => {
    expect(mapSource).toContain('String(node.memoryId) === String(props.selectedMemoryId)')
    expect(pageSource).toContain(':selected-memory-id="detailId"')
    expect(pageSource).toContain('detailId.value = id ?? null')
  })

  it('星图卸载时清理动画与监听，避免页面切走后还在跑 rAF', () => {
    expect(mapSource).toContain('cancelAnimationFrame')
    expect(mapSource).toContain('removeEventListener')
    expect(mapSource).toContain('disconnect()')
  })
})

it('mute 只改"要不要主动提"，不碰强度', () => {
  const source = readFileSync(new URL('../MemoryPage.vue', import.meta.url), 'utf-8')
  expect(source).toContain('setMemoryMuted')
  expect(source).toContain('toggleMute')
  const body = source.slice(source.indexOf('async function toggleMute'))
  expect(body.slice(0, 800)).not.toContain('strength:')
})

describe('记忆页的层标签', () => {
  it('列表卡片与详情抽屉都显示「这是哪一层」', () => {
    expect(pageSource).toContain("memory.layer.${layer}")
    expect(pageSource).toContain('class="mem-layer"')
    expect(pageSource).toContain('class="mem-sheet__layer"')
  })
})

describe('记忆页的「被想起过几次」', () => {
  it('抽屉用 recallCount 与 lastRecalledAt 说明这条记忆被用过多少次', () => {
    expect(pageSource).toContain('detailRecallLabel')
    expect(pageSource).toContain("memory.recalledWithTime")
    expect(pageSource).toContain("memory.recalledTimes")
    expect(pageSource).toContain('.mem-sheet__recalled')
  })
})
