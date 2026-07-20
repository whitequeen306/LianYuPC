import { describe, expect, it, vi } from 'vitest'
import { effectScope, ref } from 'vue'
import { useChatScroll } from '../useChatScroll.js'

describe('useChatScroll', () => {
  it('jumpToBottom snaps instantly; default scrollToBottom stays smooth', () => {
    const scrollIntoView = vi.fn()
    const container = {
      scrollHeight: 2000,
      scrollTop: 0,
      clientHeight: 400,
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
    }
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
})
