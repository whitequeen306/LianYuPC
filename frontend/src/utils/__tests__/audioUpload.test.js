import { describe, it, expect } from 'vitest'
import { prepareAudioUpload, extensionForAudioMime } from '@/utils/audioUpload'

describe('audioUpload', () => {
  it('strips codecs from MediaRecorder mime and keeps webm extension', () => {
    const raw = new Blob([new Uint8Array([1, 2, 3])], { type: 'audio/webm;codecs=opus' })
    const prepared = prepareAudioUpload(raw)
    expect(prepared.contentType).toBe('audio/webm')
    expect(prepared.filename).toBe('voice.webm')
    expect(prepared.blob.type).toBe('audio/webm')
  })

  it('maps mime to extension', () => {
    expect(extensionForAudioMime('audio/ogg')).toBe('ogg')
    expect(extensionForAudioMime('audio/mp4')).toBe('m4a')
    expect(extensionForAudioMime('audio/wav')).toBe('wav')
  })
})
