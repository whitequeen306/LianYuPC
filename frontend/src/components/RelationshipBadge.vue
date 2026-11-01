<template>
  <div v-if="view" ref="rootRef" class="relationship-badge">
    <button
      type="button"
      class="relationship-badge__pill"
      :class="`is-${tone}`"
      :aria-expanded="expanded"
      :aria-label="`${t('relationship.title')}：${phaseLabel}`"
      @click="toggle"
    >
      <span class="relationship-badge__heart" aria-hidden="true">♥</span>
      <span class="relationship-badge__label">{{ phaseLabel }}</span>
      <span v-if="showDays" class="relationship-badge__day">
        {{ t('relationship.dayShort', { n: view.daysKnown }) }}
      </span>
      <span v-if="!view.recovery" class="relationship-badge__track" aria-hidden="true">
        <span class="relationship-badge__fill" :style="{ width: `${view.progress}%` }" />
      </span>
    </button>

    <RelationshipCard
      v-if="expanded"
      class="relationship-badge__panel"
      :view="view"
      :inner-space-body="innerSpaceBody"
    />
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import RelationshipCard from '@/components/RelationshipCard.vue'
import { resolveRelationshipView } from '@/utils/relationship'

const props = defineProps({
  // 直接吃 /api/character-state 的响应，避免在聊天页再拆一层字段
  state: { type: Object, default: null },
  innerSpaceBody: { type: String, default: '' }
})

const { t, te } = useI18n()

const rootRef = ref(null)
const expanded = ref(false)

const view = computed(() => resolveRelationshipView(props.state))

const phaseLabel = computed(() => {
  if (!view.value) return ''
  return te(view.value.phaseKey) ? t(view.value.phaseKey) : view.value.phase
})

/** 第 1 天没有信息量，别占位置。 */
const showDays = computed(() => !!view.value && view.value.daysKnown > 1)

const tone = computed(() => {
  switch (view.value?.phase) {
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

function toggle() {
  expanded.value = !expanded.value
}

function handlePointerDown(event) {
  if (!expanded.value) return
  if (rootRef.value && !rootRef.value.contains(event.target)) {
    expanded.value = false
  }
}

onMounted(() => document.addEventListener('pointerdown', handlePointerDown))
onBeforeUnmount(() => document.removeEventListener('pointerdown', handlePointerDown))
</script>

<style lang="scss" scoped>
.relationship-badge {
  position: relative;
  display: inline-flex;
}

.relationship-badge__pill {
  display: inline-flex;
  align-items: center;
  gap: $space-1;
  padding: 2px 8px;
  border: 1px solid rgba($color-pink-rgb, 0.24);
  border-radius: $radius-pill;
  background: rgba($color-pink-rgb, 0.1);
  color: $color-pink-light;
  font-size: 11px;
  font-weight: $font-weight-medium;
  line-height: 1.6;
  white-space: nowrap;
  cursor: pointer;
  transition: all $transition-fast;

  &:hover {
    background: rgba($color-pink-rgb, 0.18);
    border-color: rgba($color-pink-rgb, 0.4);
  }

  &.is-healing {
    border-color: rgba($color-info, 0.35);
    background: rgba($color-info, 0.12);
    color: $color-info;
  }

  &.is-tense {
    border-color: rgba($color-error, 0.35);
    background: rgba($color-error, 0.12);
    color: $color-error;
  }

  &.is-deep {
    border-color: rgba($color-pink-rgb, 0.45);
    background: rgba($color-pink-rgb, 0.2);
    color: $color-pink-primary;
  }
}

.relationship-badge__heart {
  font-size: 10px;
  line-height: 1;
}

.relationship-badge__day {
  color: $color-text-muted;
  font-variant-numeric: tabular-nums;
}

.relationship-badge__track {
  display: block;
  width: 30px;
  height: 3px;
  border-radius: $radius-full;
  background: rgba($color-text-muted, 0.3);
  overflow: hidden;
}

.relationship-badge__fill {
  display: block;
  height: 100%;
  border-radius: $radius-full;
  background: currentColor;
  transition: width $transition-base;
}

.relationship-badge__panel {
  position: absolute;
  top: calc(100% + 6px);
  left: 0;
  z-index: 30;
}
</style>
