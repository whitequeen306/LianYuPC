<template>
  <div class="chat-toast-host" aria-live="polite">
    <TransitionGroup name="chat-toast">
      <button
        v-for="item in toasts"
        :key="item.id"
        type="button"
        class="chat-toast"
        @click="onClick(item)"
      >
        <div class="chat-toast__top">
          <span class="chat-toast__name">{{ item.characterName }}</span>
          <span class="chat-toast__time">{{ item.timeLabel }}</span>
        </div>
        <p class="chat-toast__preview">{{ item.preview }}</p>
      </button>
    </TransitionGroup>
  </div>
</template>

<script setup>
import { useInAppMessageToast, dismissChatMessageToast } from '@/composables/useInAppMessageToast'
import { navigateToNotification } from '@/composables/useNotificationNavigation'

const { toasts } = useInAppMessageToast()

async function onClick(item) {
  dismissChatMessageToast(item.id)
  await navigateToNotification(item.raw || {
    type: 'PROACTIVE_MESSAGE',
    conversationId: item.conversationId,
    characterId: item.characterId,
    title: `${item.characterName} 给你发来消息`,
    contentPreview: item.preview,
  })
}
</script>

<style scoped lang="scss">
.chat-toast-host {
  position: fixed;
  top: calc(var(--electron-caption-height, 0px) + #{$space-3});
  left: 50%;
  transform: translateX(-50%);
  z-index: 9600;
  width: min(360px, calc(100vw - #{$space-8}));
  display: flex;
  flex-direction: column;
  gap: $space-2;
  pointer-events: none;
}

.chat-toast {
  pointer-events: auto;
  width: 100%;
  margin: 0;
  padding: $space-3 $space-4;
  border: 1px solid rgba($color-pink-rgb, 0.16);
  border-radius: $radius-md;
  background: var(--ly-bg-glass-strong, rgba(30, 39, 50, 0.96));
  color: var(--ly-text-primary);
  text-align: left;
  cursor: pointer;
  box-shadow: 0 8px 28px rgba(0, 0, 0, 0.28);
  transition: background 0.22s cubic-bezier(0.23, 1, 0.32, 1),
    transform 0.22s cubic-bezier(0.23, 1, 0.32, 1);

  &:hover {
    background: var(--ly-bg-elevated, #252f3c);
  }

  &:active {
    transform: scale(0.985);
  }
}

.chat-toast__top {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: $space-3;
  margin-bottom: $space-1;
}

.chat-toast__name {
  min-width: 0;
  flex: 1;
  font-size: 0.9375rem;
  font-weight: 600;
  color: var(--ly-text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.chat-toast__time {
  flex-shrink: 0;
  font-size: $font-size-xs;
  color: var(--ly-text-muted);
  font-variant-numeric: tabular-nums;
}

.chat-toast__preview {
  margin: 0;
  font-size: 0.8125rem;
  line-height: 1.45;
  color: var(--ly-text-secondary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.chat-toast-enter-active,
.chat-toast-leave-active {
  transition: opacity 0.28s cubic-bezier(0.23, 1, 0.32, 1),
    transform 0.28s cubic-bezier(0.23, 1, 0.32, 1);
}

.chat-toast-enter-from {
  opacity: 0;
  transform: translateY(-16px);
}

.chat-toast-leave-to {
  opacity: 0;
  transform: translateY(-8px);
}

.chat-toast-move {
  transition: transform 0.28s cubic-bezier(0.23, 1, 0.32, 1);
}
</style>
