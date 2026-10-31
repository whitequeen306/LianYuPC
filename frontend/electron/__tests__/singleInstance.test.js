import { describe, expect, it, vi } from 'vitest'
import { acquireSingleInstanceLock } from '../singleInstance.js'

function createMockApp(acquired) {
  const handlers = new Map()
  return {
    requestSingleInstanceLock: vi.fn(() => acquired),
    quit: vi.fn(),
    on: vi.fn((event, handler) => handlers.set(event, handler)),
    emit: (event, ...args) => handlers.get(event)?.(...args),
  }
}

describe('acquireSingleInstanceLock', () => {
  it('未拿到锁时立即退出，且不注册 second-instance', () => {
    const app = createMockApp(false)
    const log = vi.fn()

    const acquired = acquireSingleInstanceLock({ app, onSecondInstance: vi.fn(), log })

    expect(acquired).toBe(false)
    expect(app.quit).toHaveBeenCalledTimes(1)
    expect(app.on).not.toHaveBeenCalled()
    expect(log).toHaveBeenCalledWith(expect.stringContaining('lock held by another process'))
  })

  it('拿到锁时继续启动并监听 second-instance', () => {
    const app = createMockApp(true)

    const acquired = acquireSingleInstanceLock({ app, onSecondInstance: vi.fn() })

    expect(acquired).toBe(true)
    expect(app.quit).not.toHaveBeenCalled()
    expect(app.on).toHaveBeenCalledWith('second-instance', expect.any(Function))
  })

  it('second-instance 触发时把 argv 交给回调（用于唤起已有窗口）', () => {
    const app = createMockApp(true)
    const onSecondInstance = vi.fn()
    acquireSingleInstanceLock({ app, onSecondInstance })

    app.emit('second-instance', {}, ['YuNian.exe', '--lianyu-debug'])

    expect(onSecondInstance).toHaveBeenCalledWith({ argv: ['YuNian.exe', '--lianyu-debug'] })
  })

  it('argv 缺失时回调收到空数组而不是 undefined', () => {
    const app = createMockApp(true)
    const onSecondInstance = vi.fn()
    acquireSingleInstanceLock({ app, onSecondInstance })

    app.emit('second-instance', {})

    expect(onSecondInstance).toHaveBeenCalledWith({ argv: [] })
  })

  it('唤起失败不冒泡：已有实例不能被第二个实例的异常拖垮', () => {
    const app = createMockApp(true)
    const log = vi.fn()
    const onSecondInstance = vi.fn(() => {
      throw new Error('boom')
    })
    acquireSingleInstanceLock({ app, onSecondInstance, log })

    expect(() => app.emit('second-instance', {}, [])).not.toThrow()
    expect(log).toHaveBeenCalledWith(expect.stringContaining('boom'))
  })

  it('没有 Electron app（纯 node 环境）时不阻塞启动', () => {
    expect(acquireSingleInstanceLock({})).toBe(true)
    expect(acquireSingleInstanceLock()).toBe(true)
  })
})
