import { describe, expect, it } from 'vitest'
import { isVoiceCallPet, VOICE_CALL_PET_IDS } from '../voiceCallPets'

describe('voiceCallPets', () => {
  it('includes all square pets with voice interaction', () => {
    expect(VOICE_CALL_PET_IDS).toContain('raiden')
    expect(VOICE_CALL_PET_IDS).toContain('elysia')
    expect(VOICE_CALL_PET_IDS).toContain('yae_miko')
    expect(VOICE_CALL_PET_IDS).toContain('noelle')
    expect(VOICE_CALL_PET_IDS).toContain('klee')
    expect(VOICE_CALL_PET_IDS).toContain('ganyu')
    expect(VOICE_CALL_PET_IDS).toContain('ayaka')
    expect(VOICE_CALL_PET_IDS).toContain('erii_uesugi')
    expect(VOICE_CALL_PET_IDS).not.toContain('kurumi')
  })

  it('isVoiceCallPet matches allowlist case-insensitively', () => {
    expect(isVoiceCallPet('raiden')).toBe(true)
    expect(isVoiceCallPet('elysia')).toBe(true)
    expect(isVoiceCallPet('yae_miko')).toBe(true)
    expect(isVoiceCallPet('klee')).toBe(true)
    expect(isVoiceCallPet('Ganyu')).toBe(true)
    expect(isVoiceCallPet('erii_uesugi')).toBe(true)
    expect(isVoiceCallPet('kurumi')).toBe(false)
    expect(isVoiceCallPet('')).toBe(false)
    expect(isVoiceCallPet(null)).toBe(false)
  })
})
