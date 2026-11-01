import { describe, it, expect } from 'vitest'
import {
  diffRelationshipView,
  normalizeRelationshipScore,
  relationshipBand,
  resolveRelationshipView,
  RELATIONSHIP_DIMENSIONS,
  RELATIONSHIP_NOTICE_THRESHOLD
} from '../relationship'

const familiarState = {
  relationshipPhase: 'FAMILIAR',
  relationshipNextPhase: 'DEPENDENT',
  relationshipProgress: 42,
  relationshipDaysKnown: 12,
  relationshipStreakDays: 5,
  relationshipAwayHours: 50,
  relationshipIntimacyScore: 48,
  relationshipTrustScore: 52,
  relationshipSecurityScore: 44,
  relationshipAnticipationScore: 60,
  relationshipMilestones: [
    { key: 'PHASE_FAMILIAR', happenedAt: '2026-10-20T10:00:00' },
    { key: 'FIRST_CONTACT', happenedAt: '2026-10-08T10:00:00' }
  ]
}

describe('normalizeRelationshipScore', () => {
  it('clamps out-of-range scores into 0-100', () => {
    expect(normalizeRelationshipScore(-20)).toBe(0)
    expect(normalizeRelationshipScore(140)).toBe(100)
  })

  it('rounds decimals and falls back to 0 for junk input', () => {
    expect(normalizeRelationshipScore(42.6)).toBe(43)
    expect(normalizeRelationshipScore(null)).toBe(0)
    expect(normalizeRelationshipScore('abc')).toBe(0)
  })
})

describe('relationshipBand', () => {
  it('maps scores onto four human bands', () => {
    expect(relationshipBand(0)).toBe('low')
    expect(relationshipBand(24)).toBe('low')
    expect(relationshipBand(25)).toBe('mid')
    expect(relationshipBand(49)).toBe('mid')
    expect(relationshipBand(50)).toBe('high')
    expect(relationshipBand(74)).toBe('high')
    expect(relationshipBand(75)).toBe('deep')
  })
})

describe('resolveRelationshipView', () => {
  it('returns null when phase is missing so the badge stays hidden', () => {
    expect(resolveRelationshipView(null)).toBeNull()
    expect(resolveRelationshipView({})).toBeNull()
    expect(resolveRelationshipView({ relationshipPhase: 'SOULMATE' })).toBeNull()
  })

  it('accepts lowercase phase names from the wire', () => {
    expect(resolveRelationshipView({ relationshipPhase: 'stable_intimate' }).phase)
      .toBe('STABLE_INTIMATE')
  })

  it('exposes presence facts and the distance to the next phase', () => {
    const view = resolveRelationshipView(familiarState)
    expect(view.daysKnown).toBe(12)
    expect(view.streakDays).toBe(5)
    expect(view.awayDays).toBe(2)
    expect(view.progress).toBe(42)
    expect(view.nextPhaseKey).toBe('relationship.phase.DEPENDENT')
    expect(view.barValue).toBe(48)
  })

  it('maps the four dimensions in display order with band labels', () => {
    const view = resolveRelationshipView(familiarState)
    expect(view.dimensions.map((dimension) => dimension.key))
      .toEqual(RELATIONSHIP_DIMENSIONS.map((dimension) => dimension.key))
    expect(view.dimensions.map((dimension) => dimension.value)).toEqual([48, 52, 44, 60])
    expect(view.dimensions[0].labelKey).toBe('relationship.level.intimacy.mid')
    expect(view.dimensions[1].labelKey).toBe('relationship.level.trust.high')
  })

  it('sorts milestones chronologically', () => {
    const view = resolveRelationshipView(familiarState)
    expect(view.milestones.map((item) => item.key)).toEqual(['FIRST_CONTACT', 'PHASE_FAMILIAR'])
  })

  it('treats injured/repairing as side branches without a progress bar', () => {
    const view = resolveRelationshipView({
      ...familiarState,
      relationshipPhase: 'INJURED',
      relationshipNextPhase: null,
      relationshipProgress: 0
    })
    expect(view.recovery).toBe(true)
    expect(view.nextPhase).toBeNull()
    expect(view.progress).toBe(0)
  })
})

describe('diffRelationshipView', () => {
  it('reports a phase jump', () => {
    const next = { ...familiarState, relationshipPhase: 'DEPENDENT' }
    const diff = diffRelationshipView(familiarState, next)
    expect(diff.phaseChanged).toEqual({ from: 'FAMILIAR', to: 'DEPENDENT' })
  })

  it('ignores noise below the notice threshold', () => {
    const next = {
      ...familiarState,
      relationshipIntimacyScore: familiarState.relationshipIntimacyScore + RELATIONSHIP_NOTICE_THRESHOLD - 1
    }
    expect(diffRelationshipView(familiarState, next).deltas).toEqual([])
  })

  it('reports meaningful dimension moves with sign', () => {
    const next = {
      ...familiarState,
      relationshipIntimacyScore: 54,
      relationshipSecurityScore: 36
    }
    const deltas = diffRelationshipView(familiarState, next).deltas
    expect(deltas).toEqual(
      expect.arrayContaining([
        { key: 'intimacy', delta: 6 },
        { key: 'security', delta: -8 }
      ])
    )
  })

  it('stays quiet when either frame is unreadable', () => {
    expect(diffRelationshipView(null, familiarState)).toEqual({ phaseChanged: null, deltas: [] })
    expect(diffRelationshipView(familiarState, {})).toEqual({ phaseChanged: null, deltas: [] })
  })
})
