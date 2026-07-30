import { describe, expect, it } from 'vitest'
import { isVoiceCallPet, VOICE_CALL_PET_IDS } from '../voiceCallPets'

describe('voiceCallPets', () => {
  it('includes raiden and newly enrolled call pets', () => {
    expect(VOICE_CALL_PET_IDS).toContain('raiden')
    expect(VOICE_CALL_PET_IDS).toContain('yae_miko')
    expect(VOICE_CALL_PET_IDS).toContain('noelle')
    expect(VOICE_CALL_PET_IDS).not.toContain('kurumi')
  })

  it('isVoiceCallPet matches allowlist case-insensitively', () => {
    expect(isVoiceCallPet('raiden')).toBe(true)
    expect(isVoiceCallPet('yae_miko')).toBe(true)
    expect(isVoiceCallPet('kurumi')).toBe(false)
    expect(isVoiceCallPet('klee')).toBe(false)
    expect(isVoiceCallPet('')).toBe(false)
    expect(isVoiceCallPet(null)).toBe(false)
  })
})
