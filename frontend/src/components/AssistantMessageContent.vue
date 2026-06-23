<template>
  <component :is="tag" v-if="displayText" :class="rootClass">
    <template v-if="hasStyledInner">
      <template v-for="(segment, index) in segments" :key="index">
        <span v-if="segment.type === 'inner'" class="assistant-msg__inner">{{ segment.text }}</span>
        <span v-else class="assistant-msg__speech">{{ segment.text }}</span>
      </template>
    </template>
    <template v-else>{{ displayText }}</template>
  </component>
</template>

<script setup>
import { computed } from 'vue'
import { parseInnerThoughtSegments, stripInnerThoughts } from '@/utils/innerThoughtFilter'

const props = defineProps({
  content: { type: String, default: '' },
  showInnerThoughts: { type: Boolean, default: true },
  /** chat = 单聊深色对话框；group = 群聊浅色气泡 */
  variant: {
    type: String,
    default: 'group',
    validator: value => value === 'chat' || value === 'group'
  },
  tag: {
    type: String,
    default: 'span'
  },
  extraClass: {
    type: [String, Array, Object],
    default: ''
  }
})

const displayText = computed(() => stripInnerThoughts(props.content, props.showInnerThoughts))

const segments = computed(() => {
  if (!props.showInnerThoughts || !props.content) return []
  return parseInnerThoughtSegments(props.content)
})

const hasStyledInner = computed(() =>
  props.showInnerThoughts && segments.value.some(segment => segment.type === 'inner')
)

const rootClass = computed(() => [
  'assistant-msg',
  props.variant === 'chat' ? 'assistant-msg--chat' : 'assistant-msg--group',
  props.extraClass
])
</script>

<style lang="scss" scoped>
.assistant-msg {
  white-space: pre-wrap;
  word-break: break-word;

  &__speech {
    font-style: normal;
  }

  &__inner {
    font-style: italic;
  }

  &--chat &__speech {
    color: var(--ly-assistant-msg-speech);
  }

  &--chat &__inner {
    color: var(--ly-assistant-msg-inner);
  }

  &--group &__speech {
    color: $color-text-primary;
  }

  &--group &__inner {
    color: $color-text-muted;
  }
}
</style>
