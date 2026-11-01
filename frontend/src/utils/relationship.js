/**
 * 关系进度的纯展示映射。
 *
 * 后端（CharacterStateQueryService）只回 phase 枚举 + 分数 + 陪伴事实 + 里程碑 key，
 * 文案全部由 i18n 决定 —— 换语言不需要改接口。
 *
 * 展示原则：**用户感觉不到分数，只感觉到变化**。所以这里除了当前值，
 * 还要产出「距离下一阶段还差多少」和「这一轮到底动了什么」（diff）。
 */

export const RELATIONSHIP_PHASES = [
  'TESTING',
  'FAMILIAR',
  'DEPENDENT',
  'REPAIRING',
  'INJURED',
  'STABLE_INTIMATE'
]

/** 受伤 / 修复是支线状态：没有「下一阶段」，只讲怎么回来。 */
export const RELATIONSHIP_RECOVERY_PHASES = ['INJURED', 'REPAIRING']

/** 展示顺序：先亲密与信任（用户最关心的两条），再安心与期待。 */
export const RELATIONSHIP_DIMENSIONS = [
  { key: 'intimacy', field: 'relationshipIntimacyScore' },
  { key: 'trust', field: 'relationshipTrustScore' },
  { key: 'security', field: 'relationshipSecurityScore' },
  { key: 'anticipation', field: 'relationshipAnticipationScore' }
]

export const RELATIONSHIP_BANDS = ['low', 'mid', 'high', 'deep']

/** 变化提示的静默阈值：小于这个值的波动不打扰用户。 */
export const RELATIONSHIP_NOTICE_THRESHOLD = 3

export function normalizeRelationshipScore(value) {
  const parsed = Number(value)
  if (!Number.isFinite(parsed)) return 0
  return Math.max(0, Math.min(100, Math.round(parsed)))
}

export function relationshipBand(value) {
  const score = normalizeRelationshipScore(value)
  if (score >= 75) return 'deep'
  if (score >= 50) return 'high'
  if (score >= 25) return 'mid'
  return 'low'
}

function normalizePhase(raw) {
  const phase = typeof raw === 'string' ? raw.trim().toUpperCase() : ''
  return RELATIONSHIP_PHASES.includes(phase) ? phase : null
}

/**
 * 缺少 relationshipPhase（旧后端 / 降级）时返回 null：宁可不显示，
 * 也不要给用户看一个伪造的「刚认识 0%」。
 */
export function resolveRelationshipView(state) {
  const phase = normalizePhase(state?.relationshipPhase)
  if (!phase) return null

  const dimensions = RELATIONSHIP_DIMENSIONS.map(({ key, field }) => {
    const value = normalizeRelationshipScore(state[field])
    return {
      key,
      value,
      band: relationshipBand(value),
      labelKey: `relationship.level.${key}.${relationshipBand(value)}`
    }
  })
  const intimacy = dimensions.find((dimension) => dimension.key === 'intimacy')
  const nextPhase = normalizePhase(state?.relationshipNextPhase)
  const recovery = RELATIONSHIP_RECOVERY_PHASES.includes(phase)
  const milestones = Array.isArray(state?.relationshipMilestones)
    ? state.relationshipMilestones
      .filter((item) => item && typeof item.key === 'string')
      .map((item) => ({
        key: item.key,
        happenedAt: item.happenedAt || null,
        labelKey: `relationship.milestone.${item.key}`
      }))
      .sort((a, b) => String(a.happenedAt || '').localeCompare(String(b.happenedAt || '')))
    : []

  return {
    phase,
    phaseKey: `relationship.phase.${phase}`,
    descriptionKey: `relationship.desc.${phase}`,
    recovery,
    nextPhase: recovery ? null : nextPhase,
    nextPhaseKey: recovery || !nextPhase ? null : `relationship.phase.${nextPhase}`,
    progress: recovery ? 0 : normalizeRelationshipScore(state?.relationshipProgress),
    daysKnown: Math.max(1, Number(state?.relationshipDaysKnown) || 1),
    streakDays: Math.max(0, Number(state?.relationshipStreakDays) || 0),
    awayDays: Math.floor(Math.max(0, Number(state?.relationshipAwayHours) || 0) / 24),
    milestones,
    dimensions,
    // 徽章上的小进度条沿用亲密度，跨阶段也单调，不会出现「越亲密条越短」
    barValue: intimacy ? intimacy.value : 0
  }
}

/**
 * 对比两帧关系状态，找出「值得告诉用户」的变化。
 * 阈值内的小波动返回空，避免每说一句话就弹提示。
 */
export function diffRelationshipView(previous, next) {
  const before = resolveRelationshipView(previous)
  const after = resolveRelationshipView(next)
  if (!after) return { phaseChanged: null, deltas: [] }
  if (!before) return { phaseChanged: null, deltas: [] }

  const phaseChanged = before.phase === after.phase
    ? null
    : { from: before.phase, to: after.phase }

  const deltas = []
  for (const dimension of after.dimensions) {
    const previousValue = before.dimensions.find((item) => item.key === dimension.key)?.value ?? 0
    const delta = dimension.value - previousValue
    if (Math.abs(delta) >= RELATIONSHIP_NOTICE_THRESHOLD) {
      deltas.push({ key: dimension.key, delta })
    }
  }
  return { phaseChanged, deltas }
}
