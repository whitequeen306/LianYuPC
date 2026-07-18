import { describe, expect, it } from 'vitest'

import {
  getPetById,
  getPetFixedVoiceUrl,
  getPetVoiceRate,
  isValidPetId,
  petHasInteractiveVoice,
} from '../petCatalog'

describe('petCatalog', () => {
  it('includes kurumi as a valid pet', () => {
    expect(isValidPetId('kurumi')).toBe(true)
    expect(getPetById('kurumi').nameZh).toBe('时崎狂三')
  })

  it('uses a faster voice rate for elysia', () => {
    expect(getPetVoiceRate('elysia')).toBe(1.2)
    expect(getPetVoiceRate('kurumi')).toBe(1)
  })

  it('marks vc pets as having interactive voice', () => {
    expect(petHasInteractiveVoice('raiden')).toBe(true)
    expect(petHasInteractiveVoice('kurumi')).toBe(false)
    expect(getPetById('raiden').fixedVoiceLines.click).toBeTruthy()
    expect(getPetFixedVoiceUrl('raiden', 'run')).toContain('pet/voice/raiden/run.wav')
  })

  it('defines chat fixed lines for enter noon evening', () => {
    const lines = getPetById('elysia').fixedVoiceLines
    expect(lines.enter).toBeTruthy()
    expect(lines.noon).toBeTruthy()
    expect(lines.evening).toBeTruthy()
  })
})
