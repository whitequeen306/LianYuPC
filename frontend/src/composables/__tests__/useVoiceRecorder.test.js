import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { useVoiceRecorder } from '@/composables/useVoiceRecorder'

describe('useVoiceRecorder', () => {
  beforeEach(() => {
    class MockMediaRecorder {
      constructor(stream, opts) {
        this.stream = stream
        this.mimeType = opts?.mimeType || 'audio/webm'
        this.ondataavailable = null
        this.onstop = null
        this.onerror = null
      }
      start() {
        // no-op; data emitted on stop for deterministic tests
      }
      stop() {
        this.ondataavailable?.({ data: new Blob(['abc'], { type: this.mimeType }) })
        this.onstop?.()
      }
    }
    global.MediaRecorder = MockMediaRecorder
    global.MediaRecorder.isTypeSupported = () => true
    Object.defineProperty(navigator, 'mediaDevices', {
      value: {
        getUserMedia: vi.fn().mockResolvedValue({
          getTracks: () => [{ stop: vi.fn() }],
        }),
      },
      configurable: true,
    })
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('returns blob after stop', async () => {
    const { recording, start, stop } = useVoiceRecorder()
    await start()
    expect(recording.value).toBe(true)
    const blob = await stop()
    expect(recording.value).toBe(false)
    expect(blob).toBeInstanceOf(Blob)
    expect(blob.size).toBeGreaterThan(0)
  })
})
