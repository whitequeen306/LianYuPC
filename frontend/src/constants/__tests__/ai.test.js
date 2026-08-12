import { describe, expect, it } from 'vitest'
import {
  PLATFORM_PROVIDER,
  PLATFORM_MODEL,
  PLATFORM_VISION_MODEL,
} from '@/constants/ai'

describe('ai constants after platform chat removal', () => {
  it('keeps platform provider alias reserved', () => {
    expect(PLATFORM_PROVIDER).toBe('platform')
  })

  it('clears deprecated platform chat model default', () => {
    expect(PLATFORM_MODEL).toBe('')
  })

  it('keeps vision model default for Alibaba VL', () => {
    expect(PLATFORM_VISION_MODEL).toBe('qwen3.7-flash')
  })
})
