<template>
  <div class="relationship-card" :class="`is-${tone}`">
    <header class="relationship-card__head">
      <span class="relationship-card__phase">{{ phaseLabel }}</span>
      <span class="relationship-card__day">{{ t('relationship.dayN', { n: view.daysKnown }) }}</span>
    </header>

    <p v-if="innerSpaceBody" class="relationship-card__voice">{{ innerSpaceBody }}</p>

    <div class="relationship-card__facts">
      <span v-if="view.daysKnown > 1">{{ t('relationship.dayN', { n: view.daysKnown }) }}</span>
      <span v-if="view.streakDays >= 2">{{ t('relationship.streak', { n: view.streakDays }) }}</span>
      <span v-if="view.awayDays >= 1">{{ t('relationship.away', { n: view.awayDays }) }}</span>
    </div>

    <div v-if="!view.recovery" class="relationship-card__progress">
      <div class="relationship-card__progress-text">
        {{ view.nextPhaseKey
          ? t('relationship.toNext', { phase: t(view.nextPhaseKey), percent: 100 - view.progress })
          : t('relationship.steady') }}
      </div>
      <div class="relationship-card__track" aria-hidden="true">
        <span class="relationship-card__fill" :style="{ width: `${view.progress}%` }" />
      </div>
    </div>
    <p v-else class="relationship-card__recovery">
      {{ t(`relationship.recoveryHint.${view.phase}`) }}
    </p>

    <ul class="relationship-card__levels">
      <li v-for="dimension in view.dimensions" :key="dimension.key" class="relationship-card__level">
        <span class="relationship-card__level-name">{{ t(`relationship.dimension.${dimension.key}`) }}</span>
        <span class="relationship-card__level-track" aria-hidden="true">
          <span
            class="relationship-card__level-fill"
            :style="{ width: `${dimension.value}%` }"
          />
        </span>
        <span class="relationship-card__level-text">{{ t(dimension.labelKey) }}</span>
      </li>
    </ul>

    <div v-if="view.milestones.length" class="relationship-card__timeline">
      <p class="relationship-card__timeline-title">{{ t('relationship.timelineTitle') }}</p>
      <ul>
        <li v-for="item in view.milestones" :key="item.key" class="relationship-card__moment">
          <span class="relationship-card__dot" aria-hidden="true" />
          <span class="relationship-card__moment-text">{{ t(item.labelKey) }}</span>
          <span v-if="item.happenedAt" class="relationship-card__moment-date">
            {{ formatMoment(item.happenedAt) }}
          </span>
        </li>
      </ul>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'

const props = defineProps({
  view: { type: Object, required: true },
  innerSpaceBody: { type: String, default: '' }
})

const { t, te } = useI18n()

const phaseLabel = computed(() => {
  const key = props.view.phaseKey
  return te(key) ? t(key) : props.view.phase
})

/**
 * 受伤 / 修复用色调区分，不靠文案：用户感到对方变冷时，
 * 需要一眼知道「这是关系状态，不是我做错了什么」。
 */
const tone = computed(() => {
  switch (props.view.phase) {
    case 'INJURED':
      return 'tense'
    case 'REPAIRING':
      return 'healing'
    case 'STABLE_INTIMATE':
      return 'deep'
    default:
      return 'normal'
  }
})

function formatMoment(value) {
  const parsed = new Date(value)
  if (Number.isNaN(parsed.getTime())) return ''
  const month = String(parsed.getMonth() + 1).padStart(2, '0')
  const day = String(parsed.getDate()).padStart(2, '0')
  return `${month}-${day}`
}
</script>

<style lang="scss" scoped>
.relationship-card {
  width: 268px;
  padding: $space-3;
  border: 1px solid rgba($color-pink-rgb, 0.18);
  border-radius: $radius-md;
  background: $color-bg-elevated;
  box-shadow: $shadow-md;
  color: $color-text-secondary;
  font-size: 11px;
  line-height: $line-height-normal;

  &.is-healing {
    border-color: rgba($color-info, 0.32);
  }

  &.is-tense {
    border-color: rgba($color-error, 0.32);
  }
}

.relationship-card__head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: $space-2;
}

.relationship-card__phase {
  font-size: $font-size-sm;
  font-weight: $font-weight-semibold;
  color: $color-pink-light;
}

.is-healing .relationship-card__phase {
  color: $color-info;
}

.is-tense .relationship-card__phase {
  color: $color-error;
}

.relationship-card__day {
  color: $color-text-muted;
  font-variant-numeric: tabular-nums;
}

.relationship-card__voice {
  margin: $space-2 0 0;
  color: $color-text-primary;
  line-height: $line-height-relaxed;
}

.relationship-card__facts {
  display: flex;
  flex-wrap: wrap;
  gap: $space-2;
  margin-top: $space-2;
  color: $color-text-muted;

  span + span::before {
    content: '·';
    margin-right: $space-2;
    opacity: 0.6;
  }
}

.relationship-card__progress {
  margin-top: $space-3;
}

.relationship-card__progress-text {
  color: $color-text-secondary;
  margin-bottom: 6px;
}

.relationship-card__track {
  display: block;
  height: 4px;
  border-radius: $radius-full;
  background: rgba($color-text-muted, 0.24);
  overflow: hidden;
}

.relationship-card__fill {
  display: block;
  height: 100%;
  border-radius: $radius-full;
  background: $color-pink-primary;
  transition: width $transition-base;
}

.relationship-card__recovery {
  margin: $space-3 0 0;
  padding: $space-2;
  border-radius: $radius-sm;
  background: rgba($color-text-muted, 0.12);
  color: $color-text-secondary;
}

.relationship-card__levels {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin: $space-3 0 0;
  padding: 0;
  list-style: none;
}

.relationship-card__level {
  display: flex;
  align-items: center;
  gap: $space-2;
}

.relationship-card__level-name {
  flex: 0 0 34px;
  color: $color-text-muted;
}

.relationship-card__level-track {
  flex: 1 1 auto;
  height: 3px;
  border-radius: $radius-full;
  background: rgba($color-text-muted, 0.24);
  overflow: hidden;
}

.relationship-card__level-fill {
  display: block;
  height: 100%;
  border-radius: $radius-full;
  background: rgba($color-pink-rgb, 0.75);
  transition: width $transition-base;
}

.relationship-card__level-text {
  flex: 0 0 66px;
  text-align: right;
  color: $color-text-secondary;
}

.relationship-card__timeline {
  margin-top: $space-3;
  padding-top: $space-2;
  border-top: 1px solid rgba($color-text-muted, 0.18);
}

.relationship-card__timeline-title {
  margin: 0 0 $space-1;
  color: $color-text-muted;
}

.relationship-card__timeline ul {
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.relationship-card__moment {
  display: flex;
  align-items: center;
  gap: $space-2;
}

.relationship-card__dot {
  flex: 0 0 5px;
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: $color-pink-primary;
  opacity: 0.8;
}

.relationship-card__moment-text {
  flex: 1 1 auto;
  color: $color-text-secondary;
}

.relationship-card__moment-date {
  flex: 0 0 auto;
  color: $color-text-muted;
  font-variant-numeric: tabular-nums;
}
</style>
