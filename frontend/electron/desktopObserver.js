/**
 * 桌面感知模块 — 定时截图 + 窗口检测 → 发送到后端分析 → 返回主动问候。
 */
import { net, desktopCapturer, screen } from 'electron'

const OBSERVE_INTERVAL_MS = 15 * 60 * 1000 // 15 分钟
const MIN_DELAY_AFTER_WAKE = 30_000 // 唤醒后延迟 30 秒
const MAX_GREETING_LENGTH = 160

let observeTimer = null
let lastWindowTitle = ''
let lastGreetingTitle = ''
let lastGreetingTime = 0
let lastApiOrigin = ''
let lastAuthToken = ''
let lastPersona = ''
let lastPetId = ''
let onGreeting = null
let isPaused = false
let idleSince = 0
let lastMousePos = { x: 0, y: 0 }

export function startDesktopObserver({ apiOrigin, authToken, persona, petId, onGreeting: cb }) {
  stopDesktopObserver()
  if (!apiOrigin || !authToken || !persona || !onGreeting) return false
  lastApiOrigin = apiOrigin
  lastAuthToken = authToken
  lastPersona = persona
  lastPetId = petId
  onGreeting = cb
  isPaused = false
  idleSince = Date.now()
  lastMousePos = screen.getCursorScreenPoint()

  scheduleNext(10_000) // 首次 10 秒后触发
  startIdleDetection()
  return true
}

export function stopDesktopObserver() {
  clearTimeout(observeTimer)
  clearInterval(idleTimer)
  observeTimer = null
  idleTimer = null
  onGreeting = null
  lastAuthToken = ''
}

export function onWindowChanged() {
  if (!onGreeting || isPaused) return
  // 窗口切换额外触发，但需距上次问候 > 30 秒避免频繁
  if (Date.now() - lastGreetingTime < 30_000) return
  clearTimeout(observeTimer)
  scheduleNext(5_000)
}

function scheduleNext(delayMs = OBSERVE_INTERVAL_MS) {
  clearTimeout(observeTimer)
  observeTimer = setTimeout(runObserve, delayMs)
}

let idleTimer = null
function startIdleDetection() {
  clearInterval(idleTimer)
  idleTimer = setInterval(() => {
    if (!onGreeting) return
    const pos = screen.getCursorScreenPoint()
    if (pos.x !== lastMousePos.x || pos.y !== lastMousePos.y) {
      lastMousePos = pos
      idleSince = Date.now()
      if (isPaused) {
        isPaused = false
        scheduleNext(MIN_DELAY_AFTER_WAKE)
      }
    } else if (!isPaused && Date.now() - idleSince > 10 * 60_000) {
      // 10 分钟无操作 → 暂停
      isPaused = true
      clearTimeout(observeTimer)
    }
  }, 30_000)
}

async function runObserve() {
  if (!onGreeting || isPaused) {
    scheduleNext()
    return
  }

  try {
    // 1. 获取前台窗口标题
    const windowTitle = await getActiveWindowTitle()

    // 2. 截屏
    const screenshot = await captureScreen()
    if (!screenshot) {
      scheduleNext()
      return
    }

    // 3. 去重：同一窗口 + 同一 pet 15 分钟内不重复
    const key = `${lastPetId}::${windowTitle}`
    if (key === lastGreetingTitle && Date.now() - lastGreetingTime < OBSERVE_INTERVAL_MS) {
      scheduleNext()
      return
    }

    // 4. 发到后端
    const apiUrl = `${lastApiOrigin}/api/desktop/observe`
    const body = JSON.stringify({
      imageBase64: screenshot,
      windowTitle,
      persona: lastPersona,
      petId: lastPetId,
    })

    const req = net.request({
      method: 'POST',
      url: apiUrl,
      headers: {
        'Content-Type': 'application/json',
        'lianyu-token': lastAuthToken,
      },
    })

    const response = await new Promise((resolve, reject) => {
      let data = ''
      req.on('response', (res) => {
        res.on('data', (chunk) => (data += chunk.toString('utf-8')))
        res.on('end', () => {
          try {
            resolve(JSON.parse(data))
          } catch {
            reject(new Error('Invalid JSON response'))
          }
        })
        res.on('error', reject)
      })
      req.on('error', reject)
      req.end(body)
    })

    // 5. 解析结果
    const greeting = (response && response.data && response.data.greeting)
      ? String(response.data.greeting).trim().slice(0, MAX_GREETING_LENGTH)
      : null

    if (greeting) {
      lastGreetingTitle = key
      lastGreetingTime = Date.now()
      lastWindowTitle = windowTitle
      onGreeting({
        text: greeting,
        audioBase64: response?.data?.audioBase64 || '',
        audioMimeType: response?.data?.audioMimeType || 'audio/wav',
      })
    }
  } catch (e) {
    console.warn('[desktopObserver] observe failed:', e?.message || e)
  }

  scheduleNext()
}

async function captureScreen() {
  try {
    const sources = await desktopCapturer.getSources({ types: ['screen'], thumbnailSize: { width: 1280, height: 720 } })
    if (!sources.length) return null
    const img = sources[0].thumbnail
    if (!img || img.isEmpty()) return null
    return img.toPNG().toString('base64')
  } catch {
    return null
  }
}

async function getActiveWindowTitle() {
  try {
    const { activeWin } = await import('active-win')
    const win = await activeWin()
    if (win) {
      return win.title || win.owner?.name || '未知'
    }
  } catch {
    // active-win might not be installed
  }
  return '未知'
}
