/**
 * 桌面能力设置（`userData/capability-settings.json`）。
 *
 * 由 `mcp-settings.json`（v1）迁移而来：
 *
 * | v1 | v2 | 说明 |
 * |---|---|---|
 * | `enabled` | `enabled` | 总开关 |
 * | `autoApprove` | —（丢弃） | 默认策略已不再对 R2 打扰，R3 永不可免确认 |
 * | `useLocalSource` / `command` / `args` / `cwd` | —（丢弃） | 不再有子进程，保留只会误导用户以为还能填 |
 *
 * 迁移是**只读一次**：读旧文件 → 写新文件 → 给旧文件打 `_migrated` 标记（不删，
 * 用户手改过的配置留档）；之后只读新文件。
 *
 * 本模块不依赖 electron，`dir` 由调用方注入（主进程传 `app.getPath('userData')`，
 * 单测传临时目录），保证迁移逻辑可测。
 */

import fsDefault from 'node:fs'
import pathDefault from 'node:path'

export const LEGACY_FILE = 'mcp-settings.json'
export const SETTINGS_FILE = 'capability-settings.json'
export const SETTINGS_VERSION = 2

export const PERCEPTION_MODES = ['auto', 'screen_only', 'elements_only']

export const DEFAULTS = Object.freeze({
  v: SETTINGS_VERSION,
  // 总开关：关闭后 desk_* 不进任何一次模型调用
  enabled: false,
  // 「所有有副作用的操作都先问我」；默认只在 R3 与隐私类动作时问
  strictConfirm: false,
  // auto = 截图 + 元素清单；screen_only = 只截图；elements_only = 只取元素（不上传图）
  perception: 'auto',
  maxDeskSteps: 6,
  privacy: {
    blockedApps: [],
    keepFramesInMemoryOnly: true,
  },
  migratedFrom: '',
})

const MAX_BLOCKED_APPS = 64
const MAX_APP_LENGTH = 64

function clampSteps(value) {
  const n = Number(value)
  if (!Number.isFinite(n)) return DEFAULTS.maxDeskSteps
  return Math.min(12, Math.max(1, Math.round(n)))
}

function normalizeBlockedApps(list) {
  if (!Array.isArray(list)) return []
  const out = []
  for (const item of list) {
    const text = String(item ?? '').trim()
    if (!text || text.length > MAX_APP_LENGTH) continue
    if (out.includes(text)) continue
    out.push(text)
    if (out.length >= MAX_BLOCKED_APPS) break
  }
  return out
}

export function normalizeCapabilitySettings(raw) {
  const src = raw && typeof raw === 'object' ? raw : {}
  const perception = PERCEPTION_MODES.includes(src.perception) ? src.perception : DEFAULTS.perception
  const privacy = src.privacy && typeof src.privacy === 'object' ? src.privacy : {}
  return {
    v: SETTINGS_VERSION,
    enabled: src.enabled === true,
    strictConfirm: src.strictConfirm === true,
    perception,
    maxDeskSteps: clampSteps(src.maxDeskSteps),
    privacy: {
      blockedApps: normalizeBlockedApps(privacy.blockedApps),
      keepFramesInMemoryOnly: privacy.keepFramesInMemoryOnly !== false,
    },
    migratedFrom: typeof src.migratedFrom === 'string' ? src.migratedFrom.slice(0, 120) : '',
  }
}

/** v1（mcp-settings.json）→ v2 的部分字段。 */
export function migrateFromLegacy(legacy) {
  const old = legacy && typeof legacy === 'object' ? legacy : {}
  return {
    enabled: old.enabled === true,
    migratedFrom: `${LEGACY_FILE}@v1`,
  }
}

export function createCapabilitySettings({ dir, fsImpl = fsDefault, pathImpl = pathDefault } = {}) {
  if (!dir) throw new Error('capability settings: dir is required')

  const newPath = pathImpl.join(dir, SETTINGS_FILE)
  const legacyPath = pathImpl.join(dir, LEGACY_FILE)

  function readFileJson(file) {
    try {
      return JSON.parse(fsImpl.readFileSync(file, 'utf8'))
    } catch {
      return null
    }
  }

  function readRaw() {
    const existing = readFileJson(newPath)
    if (existing) return { settings: normalizeCapabilitySettings(existing), migrated: false }
    const legacy = readFileJson(legacyPath)
    if (!legacy) return { settings: normalizeCapabilitySettings(DEFAULTS), migrated: false }
    // 首次升级：把旧配置映射过来，并留档标记
    const settings = normalizeCapabilitySettings({ ...DEFAULTS, ...migrateFromLegacy(legacy) })
    try {
      write(settings)
    } catch {
      /* 写失败不阻断启动，下次再试 */
    }
    try {
      const annotated = { ...legacy, _migrated: true, _migratedTo: SETTINGS_FILE }
      fsImpl.writeFileSync(legacyPath, JSON.stringify(annotated, null, 2))
    } catch {
      /* 旧文件只读也不影响 */
    }
    return { settings, migrated: true }
  }

  function read() {
    return readRaw().settings
  }

  function write(partial) {
    const next = normalizeCapabilitySettings({ ...read(), ...(partial || {}) })
    fsImpl.mkdirSync(pathImpl.dirname(newPath), { recursive: true })
    fsImpl.writeFileSync(newPath, JSON.stringify(next, null, 2))
    return next
  }

  return {
    file: newPath,
    legacyFile: legacyPath,
    read,
    readRaw,
    write,
  }
}
