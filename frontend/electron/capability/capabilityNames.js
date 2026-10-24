/**
 * 桌面能力名清单（主进程 ↔ 渲染端共用）。
 *
 * **唯一事实来源是 `backend-agent/app/capability/specs.py`**：schema、超时、危险等级都在那边。
 * 这里只维护「有哪些能力名」，用途是：
 * 1. 让渲染端把「桌面能力在线」注册到云端（Java 只按 `desk_` 前缀判断在线，不用 schema）；
 * 2. 设置页展示当前可用的能力。
 *
 * `capabilityNames.test.js` 会读 Python 文件比对两份清单，漂移会直接失败。
 */

export const CAPABILITY_NAMES = Object.freeze([
  'desk_screen_read',
  'desk_read_elements',
  'desk_find',
  'desk_wait',
  'desk_window_list',
  'desk_window_focus',
  'desk_app_launch',
  'desk_click',
  'desk_key',
  'desk_type',
  'desk_scroll',
  'desk_system_control',
  'desk_file_reveal',
  'desk_file_open',
  'desk_clipboard_get',
  'desk_clipboard_set',
])

/** 云端注册用的最小清单：Java 只需要名字（schema 由 Python 持有）。 */
export function capabilityManifest() {
  return CAPABILITY_NAMES.map((name) => ({
    name,
    description: `桌面能力 ${name}`,
    inputSchema: { type: 'object', properties: {} },
    // 危险等级判定在 Electron policy.js，不上报给云端（否则等于把策略写给模型看）
    dangerous: false,
  }))
}
