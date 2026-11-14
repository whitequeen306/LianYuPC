/**
 * Windows 进程清理：按命令行里的 --user-data-dir 精确识别某个号的 QQ 进程。
 *
 * 为什么需要：NapCatWinBootMain.exe 注入 hook 后以 code=0 正常退出（见
 * src/supervisor.js 里 exit code=0 那段注释），真正的 QQ 进程已经"脱手"，
 * createNapCatProcess.stop() 杀不到它。原桌面端按 bot profile 精准补杀；云端一机多号，
 * 判据必须细到每个号的 profile 目录，否则会误杀别人的号。
 *
 * wmic 在新版 Windows 上已弃用，故优先 PowerShell CIM，失败再退回 wmic。
 */
import { spawnSync } from 'node:child_process'

const PROFILE_FLAG = '--user-data-dir='

function runCapture(command, args) {
  try {
    const r = spawnSync(command, args, { encoding: 'utf8', windowsHide: true, timeout: 15000 })
    if (r.error) return ''
    return r.stdout || ''
  } catch {
    return ''
  }
}

/**
 * 列出命令行含指定 profile 目录的进程 pid。
 * @param {string} profileDir 绝对路径（大小写与斜杠形式不敏感匹配）
 * @returns {number[]}
 */
export function findQqPidsByProfile(profileDir) {
  if (process.platform !== 'win32' || !profileDir) return []
  const needle = PROFILE_FLAG + profileDir

  const viaCim = runCapture('powershell', [
    '-NoProfile',
    '-NonInteractive',
    '-Command',
    "Get-CimInstance Win32_Process -Filter \"Name='QQ.exe'\" | Select-Object ProcessId,CommandLine | ConvertTo-Json -Compress",
  ])
  const pids = parsePidsFromJson(viaCim, needle)
  if (pids.length) return pids

  // 兜底：wmic 输出 CSV，最后一列是 ProcessId
  const viaWmic = runCapture('wmic', ['process', 'where', "name='QQ.exe'", 'get', 'ProcessId,CommandLine', '/format:csv'])
  return parsePidsFromCsv(viaWmic, needle)
}

function normalize(text) {
  return String(text || '').replace(/\//g, '\\').toLowerCase()
}

function parsePidsFromJson(raw, needle) {
  if (!raw || !raw.trim()) return []
  let parsed
  try {
    parsed = JSON.parse(raw)
  } catch {
    return []
  }
  const rows = Array.isArray(parsed) ? parsed : [parsed]
  const want = normalize(needle)
  return rows
    .filter((row) => row && typeof row.CommandLine === 'string' && normalize(row.CommandLine).includes(want))
    .map((row) => Number(row.ProcessId))
    .filter((pid) => Number.isInteger(pid) && pid > 0)
}

function parsePidsFromCsv(raw, needle) {
  if (!raw) return []
  const want = normalize(needle)
  const out = []
  for (const line of raw.split(/\r?\n/)) {
    if (!line.includes(',')) continue
    if (!normalize(line).includes(want)) continue
    const last = line.trim().split(',').pop()
    const pid = Number(last)
    if (Number.isInteger(pid) && pid > 0) out.push(pid)
  }
  return out
}

/** 杀掉匹配该 profile 的所有 QQ 进程，返回已杀 pid */
export function killQqByProfile(profileDir, { log } = {}) {
  const pids = findQqPidsByProfile(profileDir)
  for (const pid of pids) {
    log?.(`[kill] taskkill QQ.exe pid=${pid} (profile=${profileDir})`)
    try {
      spawnSync('taskkill', ['/F', '/T', '/PID', String(pid)], { windowsHide: true, stdio: 'ignore', timeout: 15000 })
    } catch {
      /* 进程可能已退出 */
    }
  }
  return pids
}
