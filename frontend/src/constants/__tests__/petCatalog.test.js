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

  it('uses a slightly faster voice rate for elysia', () => {
    expect(getPetVoiceRate('elysia')).toBe(1.1)
    expect(getPetVoiceRate('kurumi')).toBe(1)
  })

  it('marks vc pets as having interactive voice', () => {
    expect(petHasInteractiveVoice('raiden')).toBe(true)
    expect(petHasInteractiveVoice('kurumi')).toBe(false)
    expect(getPetById('raiden').fixedVoiceLines.click).toBeTruthy()
    expect(getPetFixedVoiceUrl('raiden', 'run')).toContain('pet/voice/raiden/run.wav')
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
