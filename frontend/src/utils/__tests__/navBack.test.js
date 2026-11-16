import { describe, expect, test } from 'vitest'
import { resolveBack, DEFAULT_BACK_FALLBACK } from '../navBack'

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
