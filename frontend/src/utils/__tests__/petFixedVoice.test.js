import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import {
  __resetPetFixedVoiceForTests,
  playPetFixedVoice,
} from '../petFixedVoice.js'

class FakeAudio {
  constructor(src) {
    this.src = src
    this.volume = 1
    this.playbackRate = 1
    this.onended = null
    this.onerror = null
  }

  play() {
    return Promise.resolve()
  }

  pause() {}
}

describe('playPetFixedVoice', () => {
  beforeEach(() => {
    __resetPetFixedVoiceForTests()
    vi.stubGlobal('Audio', FakeAudio)
  })

  afterEach(() => {
    __resetPetFixedVoiceForTests()
    vi.unstubAllGlobals()
  })

  it('plays for vc pets and respects shared cooldown', () => {
    expect(playPetFixedVoice('raiden', 'click')).toBe(true)
    expect(playPetFixedVoice('raiden', 'run')).toBe(false)
    expect(playPetFixedVoice('kurumi', 'click')).toBe(false)
  })

  it('skips when busy flag is set', () => {
    expect(playPetFixedVoice('raiden', 'click', { busy: true })).toBe(false)
  })
})
