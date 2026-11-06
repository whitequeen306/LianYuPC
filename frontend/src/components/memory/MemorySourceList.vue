<template>
  <div class="source-list">
    <div v-if="loading" class="source-list__loading">{{ t('common.loading') }}</div>
    <p v-else-if="!messages.length" class="source-list__empty">{{ t('memory.noSources') }}</p>
    <template v-else>
      <div
        v-for="message in messages"
        :key="message.id"
        class="source-list__item"
        :class="`role-${message.role}`"
      >
        <div class="source-list__meta">
          <span class="source-list__role">
            {{ message.role === 'user' ? t('memory.roleUser') : t('memory.roleAssistant') }}
          </span>
          <span class="source-list__time">{{ formatDate(message.createdAt) }}</span>
        </div>
        <p class="source-list__content">{{ message.content }}</p>
      </div>
    </template>
  </div>
</template>

<script setup>
import { useI18n } from 'vue-i18n'
import { formatSmartTime } from '@/utils/feedTime'

defineOptions({ name: 'MemorySourceList' })

defineProps({
  messages: { type: Array, default: () => [] },
  loading: { type: Boolean, default: false }
})

const { t, locale } = useI18n()

function formatDate(value) {
  return formatSmartTime(value, { t, locale: locale.value })
}
</script>

<style lang="scss" scoped>
.source-list {
  display: flex;
  flex-direction: column;
  gap: $space-2;
}

.source-list__loading,
.source-list__empty {
  margin: 0;
  padding: $space-3;
  text-align: center;
  font-size: $font-size-sm;
  color: $color-text-muted;
}

.source-list__item {
  padding: $space-2 $space-3;
  border-radius: $radius-md;
  background: rgba($color-bg-secondary, 0.4);
  border-left: 3px solid transparent;

  &.role-user {
    border-left-color: rgba($color-pink-rgb, 0.5);
  }

  &.role-assistant {
    border-left-color: rgba($color-pink-light, 0.4);
  }
}

.source-list__meta {
  display: flex;
  align-items: baseline;
  gap: $space-2;
}

.source-list__role {
  font-size: 11px;
  font-weight: $font-weight-semibold;
  color: $color-text-secondary;
}

.source-list__time {
  font-size: 11px;
  color: $color-text-muted;
  opacity: 0.5;
}

.source-list__content {
  margin: 2px 0 0;
  font-size: $font-size-sm;
  line-height: 1.5;
  color: $color-text-secondary;
  white-space: pre-wrap;
  word-break: break-word;
}
</style>
