/**
 * QQ 桥接配置（B 方案）— 每个用户的桌面端各自保存一份本地配置。
 * 与 desktopSettings.js 同构：userData 下的 JSON 文件 + read/normalize/write。
 *
 * 字段命名与后端 lianyu-qq-bridge（A 形态）的 QqBridgeProperties 保持一致，
 * 便于将来两侧配置互通；provider 默认 "platform"（运营者共享 AI key，QQ 用户无需自有 vault）。
 */
import { app } from 'electron'
import path from 'path'
import fs from 'fs'

export const DEFAULTS = {
  enabled: false,
  napcat: {
    // NapCat 正向 WS 默认端口 3001；用户本地起 NapCat 后此处通常无需改动
    wsUrl: 'ws://127.0.0.1:3001',
    accessToken: '',
    connectTimeoutMs: 10000,
    reconnectBaseMs: 600,
    reconnectMaxMs: 6000,
  },
  binding: {
    // 收到 QQ 消息后路由到的云端会话 id（在 LianYu App 内创建会话后填入）
    conversationId: '',
    provider: 'platform',
    model: '',
    // 放行模式：'allowlist'（默认拒绝——私聊须命中 allowUsers、群聊须命中 allowGroups，空表=全拒）
    //           'open'（开放——任何 QQ 用户/群均可驱动宿主 AI，注意配额/prompt 注入风险）
    allowMode: 'allowlist',
    // QQ 号白名单（字符串数组）；allowlist 模式下私聊须命中，空 = 不放行
    allowUsers: [],
    // 群号白名单（字符串数组）；allowlist 模式下群聊须命中，空 = 不放行
    allowGroups: [],
    // 绑定会话对应的角色 id（懒建 QQ 用户会话时复用此角色；首次反查 binding.conversationId 后缓存）
    characterId: '',
    // QQ 单聊会话隔离映射：{ 'private:<qqUserId>': <conversationId> }。
    // 每个 QQ 用户独立会话+独立上下文，绝不串台；群聊一律丢弃不进此表。
    sessionMap: {},
  },
  reply: {
    // 云端异常/超时时的兜底回复；留空则不兜底
    fallbackText: '（服务暂时不可用，稍后再试）',
    timeoutMs: 120000,
    // 分段逐条发送的条间延迟：避免 napcat/QQ 风控，且更像真人连发
    segmentDelayMs: 500,
    // 随机抖动上限：在 segmentDelayMs 基础上加 0~jitterMs 的随机量，
    // 让发送间隔不固定，降低 QQ 风控判定为机器人的概率。0 = 不抖动。
    segmentJitterMs: 800,
  },
  hosting: {
    // 托管模式：'auto' = 桌面端自管 NapCat 运行时（下载/配置/拉起/扫码）；
    //           'manual' = 用户自行起本地 NapCat，仅 ws 连接（B 方案 spike 默认）
    mode: 'manual',
    // 用户首次扫码后缓存的 QQ 号；下次启动作为 quick-login 参数，免再扫
    qqUserId: '',
    // 已安装的 NapCat 版本 tag（用于检测升级，Phase 4）
    napcatVersion: '',
    // 自管运行时写入的 WebUI / 正向 WS 端口与令牌（持久化以便重启复用）
    webuiPort: 6099,
    webuiToken: '',
    wsPort: 3001,
    wsToken: '',
    // 用户是否同意自管托管（首次需在设置页确认；未同意不自动下载/拉起）
    consented: false,
  },
}

function settingsPath() {
  return path.join(app.getPath('userData'), 'qq-bridge-settings.json')
}

function asStringList(value) {
  if (!Array.isArray(value)) return []
  return value
    .map((v) => (typeof v === 'number' ? String(v) : String(v || '').trim()))
    .filter((v) => v.length > 0)
}

export function normalizeQqBridgeSettings(settings) {
  const raw = settings || {}
  const napcat = { ...DEFAULTS.napcat, ...(raw.napcat || {}) }
  // 迁移旧默认值：早期 reconnectMaxMs=30000 单次重连最长等 30s，登录后状态迟迟不变绿；
  // 本地 NapCat WS 重连无需长退避，统一下调到新默认。
  if (Number(napcat.reconnectMaxMs) >= 30000) napcat.reconnectMaxMs = DEFAULTS.napcat.reconnectMaxMs
  if (Number(napcat.reconnectBaseMs) >= 1000) napcat.reconnectBaseMs = DEFAULTS.napcat.reconnectBaseMs
  const binding = { ...DEFAULTS.binding, ...(raw.binding || {}) }
  const reply = { ...DEFAULTS.reply, ...(raw.reply || {}) }
  const hosting = { ...DEFAULTS.hosting, ...(raw.hosting || {}) }

  // conversationId 统一成字符串，便于拼 URL
  if (binding.conversationId != null) {
    binding.conversationId = String(binding.conversationId).trim()
  }
  binding.allowUsers = asStringList(binding.allowUsers)
  binding.allowGroups = asStringList(binding.allowGroups)
  // allowMode 二选一；非 'open' 一律归为 'allowlist'（默认拒绝，杜绝空表=静默全放行，issue #11）
  binding.allowMode = binding.allowMode === 'open' ? 'open' : 'allowlist'
  // characterId 字符串化（绑定角色缓存，懒建 QQ 用户会话用）
  binding.characterId = String(binding.characterId || '').trim()
  // sessionMap 须为纯对象：{ 'private:<qqUserId>': '<conversationId>' }；值统一字符串化
  if (binding.sessionMap && typeof binding.sessionMap === 'object' && !Array.isArray(binding.sessionMap)) {
    const cleaned = {}
    for (const [k, v] of Object.entries(binding.sessionMap)) {
      if (typeof k === 'string' && v != null) cleaned[k] = String(v)
    }
    binding.sessionMap = cleaned
  } else {
    binding.sessionMap = {}
  }

  // 托管字段规整：mode 二选一、QQ号/令牌字符串化、端口数值化
  hosting.mode = hosting.mode === 'auto' ? 'auto' : 'manual'
  hosting.qqUserId = String(hosting.qqUserId || '').trim()
  hosting.napcatVersion = String(hosting.napcatVersion || '').trim()
  hosting.webuiToken = typeof hosting.webuiToken === 'string' ? hosting.webuiToken : ''
  hosting.wsToken = typeof hosting.wsToken === 'string' ? hosting.wsToken : ''
  hosting.webuiPort = Number(hosting.webuiPort) || DEFAULTS.hosting.webuiPort
  hosting.wsPort = Number(hosting.wsPort) || DEFAULTS.hosting.wsPort
  hosting.consented = hosting.consented === true

  // reply 字段规整：segmentDelayMs 数值化（分段逐条发送的条间延迟）。
  // 须允许 0（"不延迟"）：Number(x) || 默认 会把 0 当假值误回落，故显式取非负有限数。
  const segDelayMs = Number(reply.segmentDelayMs)
  reply.segmentDelayMs = Number.isFinite(segDelayMs) && segDelayMs >= 0 ? segDelayMs : DEFAULTS.reply.segmentDelayMs
  const segJitterMs = Number(reply.segmentJitterMs)
  reply.segmentJitterMs = Number.isFinite(segJitterMs) && segJitterMs >= 0 ? segJitterMs : DEFAULTS.reply.segmentJitterMs

  return {
    enabled: raw.enabled === true,
    napcat,
    binding,
    reply,
    hosting,
  }
}

/**
 * 放行判定（纯函数，便于单测）。
 * - allowMode='open'：不限制，任何发送方都放行（用户显式选择，UI 须告警配额/注入风险）。
 * - allowMode='allowlist'（默认）：默认拒绝——私聊须命中 allowUsers，群聊须命中 allowGroups；
 *   空表 = 全拒（不再静默全放行，issue #11）。
 * @param {object} settings 归一化后的桥接配置
 * @param {{ userId?: string, groupId?: string, messageType?: string }} sender 解析后的发送方
 * @returns {boolean}
 */
export function isAllowedByBinding(settings, sender) {
  const mode = settings?.binding?.allowMode === 'open' ? 'open' : 'allowlist'
  if (mode === 'open') return true
  if (sender?.messageType === 'group') {
    const allowGroups = settings?.binding?.allowGroups || []
    return allowGroups.length > 0 && allowGroups.includes(String(sender.groupId))
  }
  const allowUsers = settings?.binding?.allowUsers || []
  return allowUsers.length > 0 && allowUsers.includes(String(sender.userId))
}

export function readQqBridgeSettings() {
  try {
    const raw = fs.readFileSync(settingsPath(), 'utf8')
    return normalizeQqBridgeSettings(JSON.parse(raw))
  } catch {
    return normalizeQqBridgeSettings({})
  }
}

export function writeQqBridgeSettings(partial) {
  const prev = readQqBridgeSettings()
  const merged = { ...prev, ...(partial || {}) }
  // hosting 含服务端托管字段（wsToken/webuiToken/napcatVersion 等），
  // 须深合并，避免渲染端的部分更新覆盖掉已持久化的令牌/版本
  if (partial && partial.hosting) {
    merged.hosting = { ...(prev.hosting || {}), ...(partial.hosting || {}) }
  }
  const next = normalizeQqBridgeSettings(merged)
  fs.mkdirSync(path.dirname(settingsPath()), { recursive: true })
  fs.writeFileSync(settingsPath(), JSON.stringify(next, null, 2))
  return next
}
