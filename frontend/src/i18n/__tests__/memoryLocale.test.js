import { describe, expect, it } from 'vitest'
import zh from '@/i18n/locales/zh'
import en from '@/i18n/locales/en'
import ja from '@/i18n/locales/ja'
import zhTW from '@/i18n/locales/zh-TW'
import { BRAIN_FLOWS, MEMORY_REGIONS, MEMORY_SECTORS } from '@/components/memory/memoryBrainModel'

const LOCALES = { zh, en, ja, 'zh-TW': zhTW }

function flatten(node, prefix = '', out = new Map()) {
  for (const [key, value] of Object.entries(node || {})) {
    const path = prefix ? `${prefix}.${key}` : key
    if (value && typeof value === 'object') flatten(value, path, out)
    else out.set(path, value)
  }
  return out
}

const memoryKeys = Object.fromEntries(
  Object.entries(LOCALES).map(([name, messages]) => [name, flatten(messages.memory || {}, 'memory')])
)

describe('记忆页文案四语齐', () => {
  it('四种语言的 memory.* key 集合完全一致', () => {
    const reference = [...memoryKeys.zh.keys()].sort()
    for (const [name, keys] of Object.entries(memoryKeys)) {
      expect([...keys.keys()].sort(), `${name} 的 memory 文案与 zh 不一致`).toEqual(reference)
    }
  })

  it('没有空文案占位：所有值都是非空字符串', () => {
    for (const [name, keys] of Object.entries(memoryKeys)) {
      for (const [key, value] of keys) {
        expect(typeof value, `${name}.${key}`).toBe('string')
        expect(value.trim().length, `${name}.${key} 是空的`).toBeGreaterThan(0)
      }
    }
  })

  it('星图用到的脑区、扇区、连线 key 都存在', () => {
    const required = []
    for (const region of MEMORY_REGIONS) required.push(region.nameKey, region.descKey)
    for (const sector of MEMORY_SECTORS) required.push(sector.nameKey)
    for (const flow of BRAIN_FLOWS) required.push(flow.labelKey)
    for (const [name, keys] of Object.entries(memoryKeys)) {
      for (const key of required) {
        expect(keys.has(key), `${name} 缺 ${key}`).toBe(true)
      }
    }
  })

  it('memory.brain.a11y 是给读屏用的完整句子，不是半截标签', () => {
    for (const [name, keys] of Object.entries(memoryKeys)) {
      expect(keys.get('memory.brain.a11y').length, name).toBeGreaterThan(10)
    }
  })
})
