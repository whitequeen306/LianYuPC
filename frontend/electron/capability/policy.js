/**
 * 桌面能力策略（Electron 主进程，**权威判定**）。
 *
 * 为什么权威在这里：只有主进程知道「前台是哪个进程、用户点了什么」，
 * 也只有它能在注入输入之前中止。Python 侧的同名判定只是省钱用的预过滤。
 *
 * 三条设计原则：
 * 1. **纯函数** —— 不读文件、不碰 electron API，可直接单测（策略错一次就是误删/误发）；
 * 2. **先拒后允** —— 敏感进程 → 角色上限 → 危险词表，任何一步拒绝就不再往下看；
 * 3. **策略不交给模型** —— 危险等级、denylist、词表都不进 tool schema，
 *    模型被诱导也不知道自己踩了哪条线。
 */

export const EVIDENCE_USER = 'user_request'
export const EVIDENCE_SCREEN = 'screen_content'

const RISK_ORDER = { R0: 0, R1: 1, R2: 2, R3: 3 }

/** 命中即视为「不可逆/涉钱/涉隐私」的控件文案（大小写不敏感） */
export const HIGH_RISK_LABEL_WORDS = [
  '删除', '移除', '卸载', '格式化', '清空', '覆盖',
  '支付', '付款', '转账', '购买', '下单',
  '发送', '提交', '发布', '注销', '解绑', '授权',
  'delete', 'uninstall', 'format', 'overwrite', 'pay', 'transfer', 'send', 'submit',
]

/** 前台是这些进程时，键入/按键等同于执行命令 → 升 R3 */
export const TERMINAL_PROCESSES = [
  'cmd.exe', 'powershell.exe', 'pwsh.exe', 'windowsterminal.exe',
  'wt.exe', 'conhost.exe', 'bash.exe', 'wsl.exe', 'mintty.exe',
]

/** 这些前台进程一律不碰（不截图、不注入） */
export const SENSITIVE_PROCESSES = [
  '1password.exe', 'keepass.exe', 'keepassxc.exe', 'bitwarden.exe',
  'lastpass.exe', 'logonui.exe', 'consent.exe', 'credentialuibroker.exe',
  'bank', '支付宝', '微信支付',
]

export const DEFAULT_POLICY = {
  version: 1,
  // 默认允许到 R3，但 R3 每次都要用户当面确认（确认弹窗是最后一道闸门）
  default: { maxDanger: 'R3' },
  roles: {},
  appAllowlist: [
    'notepad.exe', 'explorer.exe', 'chrome.exe', 'msedge.exe', 'firefox.exe',
    'wechat.exe', 'weixin.exe', 'cloudmusic.exe', 'qqmusic.exe', 'potplayermini64.exe',
    'code.exe', 'winword.exe', 'excel.exe', 'powerpnt.exe',
  ],
  appDenylist: [
    'mstsc.exe', 'regedit.exe', 'diskmgmt.msc', 'gpedit.msc', 'mmc.exe',
    'cmd.exe', 'powershell.exe', 'pwsh.exe', 'wt.exe',
  ],
  sensitiveProcesses: SENSITIVE_PROCESSES,
  terminalProcesses: TERMINAL_PROCESSES,
  highRiskLabelWords: HIGH_RISK_LABEL_WORDS,
}

function matchesAny(needle, patterns) {
  const text = String(needle || '').toLowerCase()
  if (!text) return false
  return (patterns || []).some((p) => {
    if (!p) return false
    const pattern = String(p).toLowerCase()
    return text.includes(pattern) || pattern.includes(text)
  })
}

export function isTerminalProcess(processName, policy = DEFAULT_POLICY) {
  return matchesAny(processName, policy.terminalProcesses)
}

export function isSensitiveProcess(processName, policy = DEFAULT_POLICY) {
  return matchesAny(processName, policy.sensitiveProcesses)
}

export function labelHitsHighRisk(label, policy = DEFAULT_POLICY) {
  return matchesAny(label, policy.highRiskLabelWords)
}

/**
 * 上下文升级：静态等级不够用时按现场情况加重。
 * - 往终端里打字 = 可能执行命令 → R3
 * - 点击文案命中危险词 = 不可逆动作 → R3
 */
export function escalateDanger(tool, args, { danger = 'R2', foregroundProcess, policy = DEFAULT_POLICY } = {}) {
  const a = args || {}
  if (tool === 'desk_type' || tool === 'desk_key') {
    if (isTerminalProcess(foregroundProcess, policy)) return 'R3'
  }
  if (tool === 'desk_click' && labelHitsHighRisk(a.label, policy)) {
    return 'R3'
  }
  return danger
}

function roleMaxDanger(policy, role) {
  const entry = (policy.roles || {})[role || 'default']
  if (entry && entry.maxDanger) return entry.maxDanger
  return (policy.default && policy.default.maxDanger) || 'R3'
}

/**
 * 即使只是 R2 也要问一次的工具：剪贴板读取涉及隐私内容。
 */
export const ALWAYS_CONFIRM_TOOLS = ['desk_clipboard_get']

/**
 * 判定一次动作。
 *
 * 确认策略（默认）：
 * - R0/R1：不打扰（只读/可逆）
 * - R2：不打扰（本地可逆），但 `strictConfirm` 打开时也问
 * - R3：**每次都问**，`strictConfirm` 与否都一样
 * - `ALWAYS_CONFIRM_TOOLS`：隐私类动作每次都问
 *
 * @param {object} input
 * @param {string} input.tool        能力名（desk_click …）
 * @param {string} input.danger      服务端给的静态等级；缺省 R1
 * @param {object} input.args        模型参数（含 evidence / label / text …）
 * @param {string} [input.foregroundProcess] 当前前台进程名
 * @param {string} [input.role]
 * @param {boolean} [input.strictConfirm] 用户选择「所有有副作用的操作都先问」
 * @param {object} [input.policy]
 * @returns {{allowed:boolean, needsConfirm:boolean, danger:string, reason:string}}
 */
export function decide({
  tool,
  danger = 'R1',
  args = {},
  foregroundProcess,
  role,
  strictConfirm = false,
  policy = DEFAULT_POLICY,
} = {}) {
  // 1) 先拒：敏感前台窗口（密码管理器/银行/登录界面）
  if (isSensitiveProcess(foregroundProcess, policy)) {
    return { allowed: false, needsConfirm: false, danger, reason: 'sensitive_window' }
  }

  const escalated = escalateDanger(tool, args, { danger, foregroundProcess, policy })

  // 2) 来源护栏：屏幕上的文字不能触发不可逆动作
  const evidence = String((args && args.evidence) || EVIDENCE_USER)
  if (evidence === EVIDENCE_SCREEN && escalated === 'R3') {
    return { allowed: false, needsConfirm: false, danger: escalated, reason: 'screen_content_high_risk' }
  }

  // 3) 角色上限（denylist 在这里表达：角色 maxDanger 低于动作等级即拒）
  const ceiling = roleMaxDanger(policy, role)
  if ((RISK_ORDER[escalated] ?? 3) > (RISK_ORDER[ceiling] ?? 2)) {
    return { allowed: false, needsConfirm: false, danger: escalated, reason: 'policy_blocked' }
  }

  // 4) 确认
  let needsConfirm = escalated === 'R3' || ALWAYS_CONFIRM_TOOLS.includes(tool)
  if (strictConfirm && (RISK_ORDER[escalated] ?? 3) >= RISK_ORDER.R2) {
    needsConfirm = true
  }
  return { allowed: true, needsConfirm, danger: escalated, reason: '' }
}

/**
 * 给确认弹窗用的一句话说清「我要做什么」。
 *
 * 这句是给人看的：用户不看工具的 JSON，只看这句话就能决定点不点「允许」。
 * 因此宁可笨拙也要具体 —— 禁止出现「执行操作」这类空话。
 */
export function describeAction(tool, args = {}) {
  const a = args || {}
  const quote = (v) => `「${String(v).slice(0, 60)}」`
  switch (tool) {
    case 'desk_click':
      if (a.label) return `点击${quote(a.label)}`
      if (a.element_id != null) return `点击界面上的第 ${a.element_id} 个控件`
      return `点击屏幕坐标 (${a.x ?? '?'}, ${a.y ?? '?'})`
    case 'desk_type': {
      const text = String(a.text || '')
      const shown = text.length > 40 ? `${text.slice(0, 40)}…（共 ${text.length} 字）` : text
      return a.submit ? `输入${quote(shown)}并回车` : `输入${quote(shown)}`
    }
    case 'desk_key':
      return `按下 ${(a.keys || []).join('+') || '按键'}`
    case 'desk_scroll':
      return Number(a.dy) < 0 ? '向上滚动' : '向下滚动'
    case 'desk_window_focus':
      return `切换到窗口${quote(a.title || a.process || '')}`
    case 'desk_app_launch':
      return `打开应用${quote(a.app || '')}${a.open ? `并打开${quote(a.open)}` : ''}`
    case 'desk_system_control':
      return {
        volume_set: `把音量调到 ${a.value ?? '?'}%`,
        volume_mute: '切换静音',
        media_play: '继续播放',
        media_pause: '暂停播放',
        media_next: '下一首',
        media_prev: '上一首',
        dnd_on: '打开勿扰',
        dnd_off: '关闭勿扰',
      }[a.action] || `调整系统设置（${a.action || '未知'}）`
    case 'desk_clipboard_get':
      return '读取剪贴板内容'
    case 'desk_clipboard_set':
      return `把${quote(String(a.text || '').slice(0, 30))}写入剪贴板`
    case 'desk_screen_read':
      return '看一眼你的屏幕'
    case 'desk_window_list':
      return '查看当前打开的窗口'
    default:
      return `执行 ${tool}`
  }
}

/** denylist 检查：这些应用连「启动」都不允许（策略里明确列为禁止）。 */
export function isAppDenied(appName, policy = DEFAULT_POLICY) {
  return matchesAny(appName, policy.appDenylist)
}

/** allowlist 检查：白名单内的启动不需要确认。 */
export function isAppAllowlisted(appName, policy = DEFAULT_POLICY) {
  return matchesAny(appName, policy.appAllowlist)
}
