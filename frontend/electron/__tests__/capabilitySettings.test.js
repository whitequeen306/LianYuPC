import { describe, it, expect } from 'vitest'

import {
  LEGACY_FILE,
  SETTINGS_FILE,
  DEFAULTS,
  normalizeCapabilitySettings,
  migrateFromLegacy,
  createCapabilitySettings,
} from '../capability/settings.js'

const DIR = '/userData'

function fakeFs(initial = {}) {
  const files = new Map(Object.entries(initial))
  return {
    files,
    readFileSync: (p) => {
      if (!files.has(p)) {
        const err = new Error(`ENOENT: ${p}`)
        err.code = 'ENOENT'
        throw err
      }
      return files.get(p)
    },
    writeFileSync: (p, data) => files.set(p, data),
    mkdirSync: () => {},
  }
}

describe('capability settings normalize', () => {
  it('defaults to disabled + auto perception + 6 steps', () => {
    expect(DEFAULTS.enabled).toBe(false)
    expect(DEFAULTS.perception).toBe('auto')
    expect(DEFAULTS.maxDeskSteps).toBe(6)
    const s = normalizeCapabilitySettings({})
    expect(s.v).toBe(2)
    expect(s.enabled).toBe(false)
    expect(s.strictConfirm).toBe(false)
    expect(s.privacy.keepFramesInMemoryOnly).toBe(true)
  })

  it('clamps step budget and rejects unknown perception', () => {
    expect(normalizeCapabilitySettings({ maxDeskSteps: 999 }).maxDeskSteps).toBe(12)
    expect(normalizeCapabilitySettings({ maxDeskSteps: 0 }).maxDeskSteps).toBe(1)
    expect(normalizeCapabilitySettings({ maxDeskSteps: 'abc' }).maxDeskSteps).toBe(6)
    expect(normalizeCapabilitySettings({ perception: 'telepathy' }).perception).toBe('auto')
    expect(normalizeCapabilitySettings({ perception: 'elements_only' }).perception)
      .toBe('elements_only')
  })

  it('normalizes blocked apps (trim / dedupe / length cap)', () => {
    const s = normalizeCapabilitySettings({
      privacy: { blockedApps: [' 1Password.exe ', '1Password.exe', '', 'x'.repeat(200)] },
    })
    expect(s.privacy.blockedApps).toEqual(['1Password.exe'])
  })

  it('maps v1 legacy enabled flag', () => {
    const partial = migrateFromLegacy({ enabled: true, useLocalSource: true, command: 'python' })
    expect(partial.enabled).toBe(true)
    expect(partial.migratedFrom).toBe(`${LEGACY_FILE}@v1`)
    expect(partial.command).toBeUndefined()
  })
})

describe('capability settings store', () => {
  it('returns defaults when neither file exists', () => {
    const store = createCapabilitySettings({ dir: DIR, fsImpl: fakeFs(), pathImpl: pathStub() })
    const { settings, migrated } = store.readRaw()
    expect(migrated).toBe(false)
    expect(settings.enabled).toBe(false)
  })

  it('migrates mcp-settings.json once and keeps the old file with a marker', () => {
    const fsImpl = fakeFs({
      [`${DIR}/${LEGACY_FILE}`]: JSON.stringify({
        enabled: true, autoApprove: false, useLocalSource: true, command: 'python', args: ['-m', 'x'], cwd: 'C:/a',
      }),
    })
    const store = createCapabilitySettings({ dir: DIR, fsImpl, pathImpl: pathStub() })

    const first = store.readRaw()
    expect(first.migrated).toBe(true)
    expect(first.settings.enabled).toBe(true)
    expect(first.settings.migratedFrom).toBe(`${LEGACY_FILE}@v1`)

    // 新文件已落盘
    const written = JSON.parse(fsImpl.files.get(`${DIR}/${SETTINGS_FILE}`))
    expect(written.enabled).toBe(true)
    expect(written.v).toBe(2)

    // 旧文件被打标记而不是被删
    const legacy = JSON.parse(fsImpl.files.get(`${DIR}/${LEGACY_FILE}`))
    expect(legacy._migrated).toBe(true)
    expect(legacy._migratedTo).toBe(SETTINGS_FILE)

    // 第二次读只读新文件，不再迁移
    const second = store.readRaw()
    expect(second.migrated).toBe(false)
    expect(second.settings.enabled).toBe(true)
  })

  it('write merges and persists normalized values', () => {
    const fsImpl = fakeFs()
    const store = createCapabilitySettings({ dir: DIR, fsImpl, pathImpl: pathStub() })
    store.write({ enabled: true, maxDeskSteps: 99 })
    const saved = JSON.parse(fsImpl.files.get(`${DIR}/${SETTINGS_FILE}`))
    expect(saved.enabled).toBe(true)
    expect(saved.maxDeskSteps).toBe(12)

    store.write({ strictConfirm: true })
    const again = JSON.parse(fsImpl.files.get(`${DIR}/${SETTINGS_FILE}`))
    expect(again.enabled).toBe(true)      // 未被覆盖掉
    expect(again.strictConfirm).toBe(true)
  })

  it('tolerates a corrupt new file by falling back to legacy/defaults', () => {
    const fsImpl = fakeFs({ [`${DIR}/${SETTINGS_FILE}`]: '{not json' })
    const store = createCapabilitySettings({ dir: DIR, fsImpl, pathImpl: pathStub() })
    expect(store.read().enabled).toBe(false)
  })
})

/** 只实现 join/dirname：真实 path 在 node 环境也能用，这里显式注入以示可替换。 */
function pathStub() {
  return {
    join: (...parts) => parts.filter(Boolean).join('/').replace(/\/+/g, '/'),
    dirname: (p) => p.split('/').slice(0, -1).join('/'),
  }
}
