import { afterEach, beforeEach, describe, expect, test } from 'vitest'
import {
  resolveBack,
  navigateBack,
  DEFAULT_BACK_FALLBACK,
  BACK_VERIFY_MS
} from '../navBack'

describe('resolveBack', () => {
  test('历史里有 /app 上一页时真的回退', () => {
    expect(resolveBack({ back: '/app/settings', current: '/app/qq-chat' })).toEqual({ type: 'back' })
  })

  test('整页 load 出来的页面没有 back，改用 push 兜底', () => {
    // Electron loadRoute / 启动恢复都是整页载入，history.state.back 为 null
    expect(resolveBack({ back: null, current: '/app/qq-chat' })).toEqual({
      type: 'push',
      path: DEFAULT_BACK_FALLBACK
    })
  })

  test('state 缺失或形状异常也走兜底，不会静默不动', () => {
    for (const state of [undefined, null, 'oops', 42, {}]) {
      expect(resolveBack(state)).toEqual({ type: 'push', path: DEFAULT_BACK_FALLBACK })
    }
  })

  test('上一页不在 /app 里时兜底，避免退到落地页被守卫弹回', () => {
    for (const back of ['/', '/login', '/register', '/launcher']) {
      expect(resolveBack({ back })).toEqual({ type: 'push', path: DEFAULT_BACK_FALLBACK })
    }
  })

  test('兜底路径可覆盖', () => {
    expect(resolveBack({ back: null }, '/app/characters')).toEqual({
      type: 'push',
      path: '/app/characters'
    })
  })
})

describe('navigateBack', () => {
  let hash
  let timers

  const fakeWindow = () => ({
    location: {
      get hash() {
        return hash
      },
      set hash(value) {
        hash = value
      }
    }
  })

  const makeRouter = (behavior) => {
    const calls = []
    return {
      calls,
      back() {
        calls.push(['back'])
        if (behavior && behavior.onBack) behavior.onBack()
      },
      push(path) {
        calls.push(['push', path])
        if (behavior && behavior.onPush) behavior.onPush(path)
      }
    }
  }

  // 把 setTimeout 换成手动触发，避免测试等真实时间
  const setTimer = (fn) => {
    timers.push(fn)
    return timers.length
  }

  const flushTimers = () => {
    while (timers.length) {
      const fn = timers.shift()
      fn()
    }
  }

  beforeEach(() => {
    hash = '#/app/qq-chat'
    timers = []
    globalThis.window = fakeWindow()
  })

  afterEach(() => {
    delete globalThis.window
  })

  test('没有上一页时直接 push 兜底，不会调用 back', () => {
    const router = makeRouter()
    navigateBack(router, { back: null, current: '/app/qq-chat' }, DEFAULT_BACK_FALLBACK, { setTimer })
    expect(router.calls).toEqual([['push', DEFAULT_BACK_FALLBACK]])
  })

  test('有上一页且 back 生效时只回退，不再 push', () => {
    const router = makeRouter({
      onBack() {
        hash = '#/app/settings'
      }
    })
    navigateBack(router, { back: '/app/settings' }, DEFAULT_BACK_FALLBACK, { setTimer })
    flushTimers()
    expect(router.calls).toEqual([['back']])
  })

  // 这条是真实安装版里复现出来的场景：state.back 有值，但 router.back() 之后 hash 一动不动。
  test('back 落空时兜底 push，绝不静默无反应', () => {
    const router = makeRouter({
      onBack() {
        // 故意不改 hash：模拟整页 load 后 history.go(-1) 落空
      }
    })
    navigateBack(router, { back: '/app/settings' }, DEFAULT_BACK_FALLBACK, { setTimer })
    flushTimers()
    expect(router.calls).toEqual([['back'], ['push', DEFAULT_BACK_FALLBACK]])
  })

  test('默认校验窗口是 400ms，足够覆盖路由守卫', () => {
    expect(BACK_VERIFY_MS).toBe(400)
  })
})
