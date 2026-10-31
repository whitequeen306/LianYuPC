import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createTurnTailGate } from '../voiceTurnTail.js'

describe('createTurnTailGate', () => {
  beforeEach(() => {
    vi.useFakeTimers()
  })
  afterEach(() => {
    vi.useRealTimers()
  })

  it('没有尾音时立即 idle', () => {
    const onIdle = vi.fn()
    const gate = createTurnTailGate({ isPlaying: () => false, onIdle })

    gate.settle()

    expect(onIdle).toHaveBeenCalledTimes(1)
    expect(gate.pending()).toBe(false)
  })

  it('尾音仍在播时保持 speaking，排空后才 idle', () => {
    let playing = true
    const onIdle = vi.fn()
    const gate = createTurnTailGate({ isPlaying: () => playing, onIdle })

    gate.settle()

    expect(onIdle).not.toHaveBeenCalled()
    expect(gate.pending()).toBe(true)

    vi.advanceTimersByTime(500)
    expect(onIdle).not.toHaveBeenCalled()

    playing = false
    vi.advanceTimersByTime(200)
    expect(onIdle).toHaveBeenCalledTimes(1)
    expect(gate.pending()).toBe(false)
  })

  it('排空后不再重复回调', () => {
    let playing = true
    const onIdle = vi.fn()
    const gate = createTurnTailGate({ isPlaying: () => playing, onIdle })
    gate.settle()

    playing = false
    vi.advanceTimersByTime(500)
    vi.advanceTimersByTime(5000)

    expect(onIdle).toHaveBeenCalledTimes(1)
  })

  it('新回合到来时 stop 撤销监视，避免把新回合压回 idle', () => {
    let playing = true
    const onIdle = vi.fn()
    const gate = createTurnTailGate({ isPlaying: () => playing, onIdle })
    gate.settle()

    gate.stop()
    playing = false
    vi.advanceTimersByTime(1000)

    expect(onIdle).not.toHaveBeenCalled()
    expect(gate.pending()).toBe(false)
  })

  it('重复 settle 只保留一个定时器', () => {
    let playing = true
    const onIdle = vi.fn()
    const gate = createTurnTailGate({ isPlaying: () => playing, onIdle })

    gate.settle()
    gate.settle()
    gate.settle()

    playing = false
    vi.advanceTimersByTime(200)

    expect(onIdle).toHaveBeenCalledTimes(1)
  })
})
