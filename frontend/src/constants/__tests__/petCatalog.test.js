import { describe, expect, it } from 'vitest'

import { getPetById, getPetVoiceRate, isValidPetId } from '../petCatalog'

describe('petCatalog', () => {
  it('includes kurumi as a valid pet', () => {
    expect(isValidPetId('kurumi')).toBe(true)
    expect(getPetById('kurumi').nameZh).toBe('时崎狂三')
  })

  it('uses a faster voice rate for elysia', () => {
    expect(getPetVoiceRate('elysia')).toBe(1.2)
    expect(getPetVoiceRate('kurumi')).toBe(1)
  })
})
