import { describe, expect, it, vi } from 'vitest'
import { effectScope, nextTick, ref } from 'vue'
import { useChatScroll } from '../useChatScroll.js'

function makeContainer(overrides = {}) {
  return {
    scrollHeight: 2000,
    scrollTop: 0,
    clientHeight: 400,
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
    ...overrides,
  }
}

describe('useChatScroll', () => {
  it('jumpToBottom snaps instantly; default scrollToBottom stays smooth', () => {
    const scrollIntoView = vi.fn()
    const container = makeContainer()
    const scope = effectScope(true)
    let api
    scope.run(() => {
      api = useChatScroll(ref(container), ref({ scrollIntoView }))
    })

    api.jumpToBottom()
    expect(container.scrollTop).toBe(2000)
    expect(scrollIntoView).not.toHaveBeenCalled()

    container.scrollTop = 0
    api.scrollToBottom({ force: true })
    expect(scrollIntoView).toHaveBeenCalledWith({ behavior: 'smooth' })

    scope.stop()
  })

  it('binds the scroll listener after the list container appears', async () => {
    const container = makeContainer()
    const containerRef = ref(null)
    const onReachTop = vi.fn()
    const scope = effectScope(true)
    scope.run(() => {
      useChatScroll(containerRef, ref(null), {
        hasMoreOlder: ref(true),
        loadingOlder: ref(false),
        onReachTop,
      })
    })

    expect(container.addEventListener).not.toHaveBeenCalled()

    containerRef.value = container
    await nextTick()

    expect(container.addEventListener).toHaveBeenCalledWith('scroll', expect.any(Function), { passive: true })
    const onScroll = container.addEventListener.mock.calls[0][1]

    container.scrollTop = 40
    onScroll()
    expect(onReachTop).toHaveBeenCalledTimes(1)

    container.scrollTop = 400
    onScroll()
    expect(onReachTop).toHaveBeenCalledTimes(1)

    scope.stop()
    expect(container.removeEventListener).toHaveBeenCalled()
  })

  it('does not load older messages while a page is already in flight', async () => {
    const container = makeContainer({ scrollTop: 10 })
    const onReachTop = vi.fn()
    const scope = effectScope(true)
    scope.run(() => {
      useChatScroll(ref(container), ref(null), {
        hasMoreOlder: ref(true),
        loadingOlder: ref(true),
        onReachTop,
      })
    })
    await nextTick()

    const onScroll = container.addEventListener.mock.calls[0][1]
    onScroll()
    expect(onReachTop).not.toHaveBeenCalled()

    scope.stop()
  })
})
