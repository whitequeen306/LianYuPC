import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'

// #10 回归：窗口切换捕获独立冷却 + 抓取期间捕获指示回调。
// 采用真实定时器（不使用 fake timers）：runObserve 是 setTimeout 触发的 fire-and-forget
// async 链，fake timers 下其微任务链无法被 advanceTimersByTimeAsync 可靠排空（实测 flaky）；
// 这里直接 await 导出的 runObserve()，让真实微任务自然排空，断言完全确定性。
// 冷却接线测试在 baseline 与断言之间只做同步调用（无 await），故不会被 vitest 调度打断。
const mocks = vi.hoisted(() => {
  let responseCb = null
  let cursorX = 0
  return {
    onCaptureStart: vi.fn(),
    onCaptureEnd: vi.fn(),
    onGreeting: vi.fn(),
    // net.request 桩：end() 后用一个微任务回放 {code:200,data:{}} 响应
    makeRequest: vi.fn(() => {
      const req = {
        on: (ev, cb) => { if (ev === 'response') responseCb = cb; return req },
        end: () => {
          Promise.resolve().then(() => {
            const res = {
              on: (ev, cb) => {
                if (ev === 'data') cb(JSON.stringify({ code: 200, data: {} }))
                else if (ev === 'end') cb()
              },
            }
            if (responseCb) responseCb(res)
          })
        },
      }
      return req
    }),
    // 桌面捕获桩：返回非空缩略图，使 captureScreen() 成功并推进 lastCaptureTime
    getSources: vi.fn(async () => [
      { thumbnail: { isEmpty: () => false, toPNG: () => Buffer.from([1, 2, 3]) } },
    ]),
    // 鼠标每次移动，避免 idle 检测暂停观察者
    getCursor: vi.fn(() => ({ x: ++cursorX, y: 0 })),
    reset: () => { cursorX = 0; responseCb = null },
  }
})

vi.mock('electron', () => ({
  net: { request: mocks.makeRequest },
  desktopCapturer: { getSources: mocks.getSources },
  screen: { getCursorScreenPoint: mocks.getCursor },
}))
vi.mock('active-win', () => ({ activeWin: async () => null }))

let observer

beforeEach(async () => {
  vi.clearAllMocks()
  mocks.reset()
  vi.resetModules()
  observer = await import('../desktopObserver.js')
  observer.startDesktopObserver({
    apiOrigin: 'http://127.0.0.1:8080',
    authToken: 'token',
    persona: 'p',
    petId: 'raiden',
    onGreeting: mocks.onGreeting,
    onCaptureStart: mocks.onCaptureStart,
    onCaptureEnd: mocks.onCaptureEnd,
  })
})

afterEach(() => {
  observer.stopDesktopObserver()
})

describe('desktopObserver (#10)', () => {
  it('runObserve 实际抓取时 onCaptureStart/onCaptureEnd 各触发一次', async () => {
    await observer.runObserve()
    expect(mocks.onCaptureStart).toHaveBeenCalledTimes(1)
    expect(mocks.onCaptureEnd).toHaveBeenCalledTimes(1)
  })

  it('窗口切换冷却边界为 10 分钟（严格小于）', () => {
    const { _isWithinWindowSwitchCooldown } = observer
    expect(_isWithinWindowSwitchCooldown(0, 5 * 60_000)).toBe(true)         // 5min < 10min → 冷却内
    expect(_isWithinWindowSwitchCooldown(0, 10 * 60_000)).toBe(false)      // 恰好 10min → 冷却外
    expect(_isWithinWindowSwitchCooldown(0, 10 * 60_000 + 1)).toBe(false)  // >10min → 冷却外
  })

  it('onWindowChanged：冷却内不调度、冷却外才调度', async () => {
    const setTimeoutSpy = vi.spyOn(globalThis, 'setTimeout')
    await observer.runObserve() // 首次抓取，lastCaptureTime = 现在
    const baseline = setTimeoutSpy.mock.calls.length
    observer.onWindowChanged()  // 冷却内 → 直接 return，不调用 scheduleNext
    expect(setTimeoutSpy.mock.calls.length).toBe(baseline)
    const dateSpy = vi.spyOn(Date, 'now').mockReturnValue(Date.now() + 11 * 60_000)
    observer.onWindowChanged()  // 冷却外 → scheduleNext(5s)
    expect(setTimeoutSpy.mock.calls.length).toBe(baseline + 1)
    dateSpy.mockRestore()
    setTimeoutSpy.mockRestore()
  })
})
