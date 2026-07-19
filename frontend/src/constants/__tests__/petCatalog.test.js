import { describe, expect, it } from 'vitest'

import {
  getPetById,
  getPetFixedVoiceLine,
  getPetFixedVoiceUrl,
  getPetVoiceRate,
  getPetVoiceVolume,
  isValidPetId,
  petHasInteractiveVoice,
} from '../petCatalog'

describe('petCatalog', () => {
  it('includes kurumi as a valid pet', () => {
    expect(isValidPetId('kurumi')).toBe(true)
    expect(getPetById('kurumi').nameZh).toBe('时崎狂三')
  })

  it('keeps elysia rate and boosts her loudness instead', () => {
    expect(getPetVoiceRate('raiden')).toBe(0.9)
    expect(getPetVoiceRate('elysia')).toBe(1.1)
    expect(getPetVoiceVolume('elysia')).toBe(1.3)
    expect(getPetVoiceVolume('kurumi')).toBe(0.9)
    expect(getPetVoiceRate('kurumi')).toBe(1)
  })

  it('marks vc pets as having interactive voice', () => {
    expect(petHasInteractiveVoice('raiden')).toBe(true)
    expect(petHasInteractiveVoice('kurumi')).toBe(false)
    expect(getPetById('raiden').fixedVoiceLines.click).toBeTruthy()
    expect(getPetFixedVoiceUrl('raiden', 'run')).toContain('pet/voice/raiden/run.wav')
    expect(getPetFixedVoiceLine('raiden', 'click').length).toBeGreaterThan(10)
  })

  it('keeps updated meet lines for raiden, ayaka, and elysia', () => {
    expect(getPetFixedVoiceLine('raiden', 'meet')).toContain('永恒方为归宿')
    expect(getPetFixedVoiceLine('ayaka', 'meet')).toContain('————')
    expect(getPetFixedVoiceLine('ayaka', 'meet')).toContain('参上')
    expect(getPetFixedVoiceLine('elysia', 'meet')).toContain('粉色妖精小姐')
  })

  it('defines chat fixed lines longer than 10 characters', () => {
    const pets = ['raiden', 'ayaka', 'ganyu', 'klee', 'elysia']
    for (const id of pets) {
      const lines = getPetById(id).fixedVoiceLines
      for (const [kind, text] of Object.entries(lines)) {
        expect(String(text).replace(/\s/g, '').length, `${id}/${kind}`).toBeGreaterThan(10)
      }
    }
  })
})
