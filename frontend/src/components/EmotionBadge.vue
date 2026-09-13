<template>
  <span class="emotion-badge" :class="emotionClass" :title="statusText">
    <span class="emotion-icon">{{ emotionIcon }}</span>
    <span class="emotion-label">{{ currentEmotion }}</span>
    <span v-if="statusText" class="emotion-status">{{ statusText }}</span>
  </span>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  currentEmotion: { type: String, default: '平静' },
  emotionIntensity: { type: Number, default: 50 },
  statusText: { type: String, default: '' }
})

const emotionIcon = computed(() => {
  const map = {
    '开心': '😊', '难过': '😢', '想念': '💭', '吃醋': '😤',
    '生气': '😠', '撒娇': '🥺', '疲惫': '😴', '兴奋': '🤩',
    '平静': '😌', '担心': '😟'
  }
  return map[props.currentEmotion] || '😌'
})

const emotionClass = computed(() => {
  return `emotion--${props.currentEmotion}`
})
</script>

<style lang="scss" scoped>
.emotion-badge {
  display: inline-flex;
  align-items: center;
  gap: $space-1;
  padding: 2px 10px;
  border-radius: $radius-pill;
  font-size: $font-size-xs;
  font-weight: $font-weight-medium;
  line-height: 1.6;
  white-space: nowrap;
  max-width: 100%;
  transition: all $transition-fast;

  &.emotion--开心 { background: rgba($emotion-happy, 0.12); color: $emotion-happy; border: 1px solid rgba($emotion-happy, 0.2); }
  &.emotion--难过 { background: rgba($emotion-sad, 0.12); color: $emotion-sad; border: 1px solid rgba($emotion-sad, 0.2); }
  &.emotion--想念 { background: rgba($emotion-miss, 0.12); color: $emotion-miss; border: 1px solid rgba($emotion-miss, 0.2); }
  &.emotion--吃醋 { background: rgba($emotion-jealous, 0.12); color: $emotion-jealous; border: 1px solid rgba($emotion-jealous, 0.2); }
  &.emotion--生气 { background: rgba($emotion-angry, 0.12); color: $emotion-angry; border: 1px solid rgba($emotion-angry, 0.2); }
  &.emotion--撒娇 { background: rgba($emotion-coquettish, 0.12); color: $emotion-coquettish; border: 1px solid rgba($emotion-coquettish, 0.2); }
  &.emotion--疲惫 { background: rgba($emotion-tired, 0.12); color: $emotion-tired; border: 1px solid rgba($emotion-tired, 0.2); }
  &.emotion--兴奋 { background: rgba($emotion-excited, 0.12); color: $emotion-excited; border: 1px solid rgba($emotion-excited, 0.2); }
  &.emotion--平静 { background: rgba($emotion-calm, 0.12); color: $emotion-calm; border: 1px solid rgba($emotion-calm, 0.2); }
  &.emotion--担心 { background: rgba($emotion-worried, 0.12); color: $emotion-worried; border: 1px solid rgba($emotion-worried, 0.2); }
}

.emotion-icon {
  font-size: 13px;
  line-height: 1;
}

.emotion-label {
  font-size: $font-size-xs;
}

.emotion-status {
  color: $color-text-muted;
  font-size: 11px;
  max-width: 120px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  margin-left: $space-1;
  padding-left: $space-1;
  border-left: 1px solid rgba($color-text-muted, 0.3);
}
</style>