import { describe, expect, it } from 'vitest'
import { createPcmPlayback } from '@/composables/pcmPlayback'

describe('createPcmPlayback', () => {
  it('exposes enqueue/clear without throwing when AudioContext is unavailable-ish', () => {
    const pb = createPcmPlayback({ sampleRate: 24000 })
    expect(typeof pb.enqueueBase64Pcm).toBe('function')
    expect(typeof pb.clear).toBe('function')
    pb.clear()
    expect(pb.isPlaying()).toBe(false)
  })
})
