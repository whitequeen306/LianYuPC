/**
 * 文件类能力：定位 / 打开。
 *
 * 两个动作的危险级别**刻意不同**：
 * - `reveal`（在资源管理器里定位）= R1：只是选中一个文件，可逆、无副作用；
 * - `open`（用默认程序打开）= R3：`shell.openPath` 会**直接执行** `.exe/.bat/.ps1`，
 *   等于一次「双击」。因此它必须过确认弹窗，且拒绝明显可执行的扩展名（双保险）。
 *
 * 路径校验只做「是不是绝对路径、是否超长、是否有控制字符」——
 * 我们不限制目录（陪伴场景里用户会说「帮我找找下载里那个合同」），
 * 但**不做任何字符串拼接**，全部走 Electron shell API，不存在命令注入面。
 */

import fsDefault from 'node:fs'
import pathDefault from 'node:path'

const MAX_PATH_CHARS = 1024
const EXECUTABLE_EXT = new Set([
  '.exe', '.bat', '.cmd', '.com', '.msi', '.ps1', '.vbs', '.js', '.jar', '.scr', '.lnk',
])

export function sanitizeFilePath(raw, { pathImpl = pathDefault } = {}) {
  const text = String(raw ?? '').trim().replace(/^"+|"+$/g, '')
  if (!text) throw new Error('path 不能为空')
  if (text.length > MAX_PATH_CHARS) throw new Error('path 过长')
  if (/[\u0000-\u001f]/.test(text)) throw new Error('path 含控制字符')
  if (!pathImpl.isAbsolute(text)) throw new Error('path 必须是绝对路径')
  return text
}

export function createFileTools({ shell, fsImpl = fsDefault, pathImpl = pathDefault, log = () => {} } = {}) {
  if (!shell) throw new Error('file tools: shell is required')

  return {
    /** 在资源管理器里定位（选中）文件或文件夹。 */
    async reveal({ path } = {}) {
      const target = sanitizeFilePath(path, { pathImpl })
      if (!fsImpl.existsSync(target)) {
        return { ok: false, error: 'file_not_found' }
      }
      shell.showItemInFolder(target)
      return { ok: true, text: `已在资源管理器里定位 ${target}`, data: { path: target } }
    },

    /** 用默认程序打开文件/文件夹。可执行扩展名拒绝在此层打开（要执行请让用户自己做）。 */
    async open({ path } = {}) {
      const target = sanitizeFilePath(path, { pathImpl })
      if (!fsImpl.existsSync(target)) {
        return { ok: false, error: 'file_not_found' }
      }
      if (EXECUTABLE_EXT.has(pathImpl.extname(target).toLowerCase())) {
        log(`file open refused (executable): ${target}`)
        return { ok: false, error: 'policy_blocked', detail: 'executable_file' }
      }
      const failure = await shell.openPath(target)
      if (failure) {
        return { ok: false, error: 'file_open_failed', detail: failure }
      }
      return { ok: true, text: `已打开 ${target}`, data: { path: target } }
    },
  }
}
