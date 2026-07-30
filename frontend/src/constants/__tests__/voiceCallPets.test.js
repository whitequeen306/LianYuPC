import { describe, expect, it } from 'vitest'
import { isVoiceCallPet, VOICE_CALL_PET_IDS } from '../voiceCallPets'

describe('voiceCallPets', () => {
  it('includes raiden and newly enrolled call pets', () => {
    expect(VOICE_CALL_PET_IDS).toContain('raiden')
    expect(VOICE_CALL_PET_IDS).toContain('kurumi')
    expect(VOICE_CALL_PET_IDS).toContain('yae_miko')
    expect(VOICE_CALL_PET_IDS).toContain('noelle')
  })

  it('isVoiceCallPet matches allowlist case-insensitively', () => {
    expect(isVoiceCallPet('raiden')).toBe(true)
    expect(isVoiceCallPet('Kurumi')).toBe(true)
    expect(isVoiceCallPet('klee')).toBe(false)
    expect(isVoiceCallPet('')).toBe(false)
    expect(isVoiceCallPet(null)).toBe(false)
  })
})
