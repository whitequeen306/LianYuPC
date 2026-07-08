import { describe, expect, it } from 'vitest'
import { effectScope } from 'vue'
import { useStreamAbort } from '@/composables/useStreamAbort'

describe('useStreamAbort', () => {
  it('aborts the previous stream when a new one starts', () => {
    const scope = effectScope()
    let api
    scope.run(() => {
      api = useStreamAbort({ abortOnUnmount: false })
    })

    const first = api.beginStream()
    const second = api.beginStream()

    expect(first.aborted).toBe(true)
    expect(second.aborted).toBe(false)
    scope.stop()
  })

  it('keeps the active stream alive on unmount when abortOnUnmount is false', () => {
    const scope = effectScope()
    let api
    let signal
    scope.run(() => {
      api = useStreamAbort({ abortOnUnmount: false })
      signal = api.beginStream()
    })

    scope.stop()

    expect(signal.aborted).toBe(false)
  })

  it('aborts the active stream on unmount when abortOnUnmount is true', () => {
    const scope = effectScope()
    let api
    let signal
    scope.run(() => {
      api = useStreamAbort({ abortOnUnmount: true })
      signal = api.beginStream()
    })

    scope.stop()

    expect(signal.aborted).toBe(true)
  })
})
