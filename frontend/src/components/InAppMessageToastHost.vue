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
        <div class="chat-toast__avatar" aria-hidden="true">
          <img
            v-if="item.avatarUrl && !brokenAvatars[item.id]"
            :src="item.avatarUrl"
            alt=""
            class="chat-toast__avatar-img"
            @error="brokenAvatars[item.id] = true"
          >
          <span v-else class="chat-toast__avatar-fallback">{{ avatarInitial(item.characterName) }}</span>
        </div>
        <div class="chat-toast__body">
          <div class="chat-toast__top">
            <span class="chat-toast__name">{{ item.characterName }}</span>
            <span class="chat-toast__time">{{ item.timeLabel }}</span>
          </div>
          <p class="chat-toast__preview">{{ item.preview }}</p>
        </div>
      </button>
    </TransitionGroup>
  </div>
</template>

<script setup>
import { reactive } from 'vue'
import { useInAppMessageToast, dismissChatMessageToast } from '@/composables/useInAppMessageToast'
import { navigateToNotification } from '@/composables/useNotificationNavigation'

const { toasts } = useInAppMessageToast()
const brokenAvatars = reactive({})

function avatarInitial(name) {
  const t = String(name || '').trim()
  return t ? t.slice(0, 1) : '·'
}

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
  display: flex;
  align-items: center;
  gap: $space-3;
  border: 1px solid var(--ly-toast-border);
  border-radius: $radius-md;
  background: var(--ly-toast-bg);
  color: var(--ly-toast-text);
  text-align: left;
  cursor: pointer;
  backdrop-filter: blur(16px);
  -webkit-backdrop-filter: blur(16px);
  transition: background 0.22s cubic-bezier(0.23, 1, 0.32, 1),
    transform 0.22s cubic-bezier(0.23, 1, 0.32, 1);

  &:hover {
    background: var(--ly-toast-bg-hover);
  }

  &:active {
    transform: scale(0.985);
  }
}

.chat-toast__avatar {
  flex-shrink: 0;
  width: 40px;
  height: 40px;
  border-radius: $radius-full;
  overflow: hidden;
  background: var(--ly-toast-avatar-bg);
  color: var(--ly-toast-avatar-text);
  display: flex;
  align-items: center;
  justify-content: center;
}

.chat-toast__avatar-img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}

.chat-toast__avatar-fallback {
  font-size: 0.9375rem;
  font-weight: 600;
  line-height: 1;
}

.chat-toast__body {
  min-width: 0;
  flex: 1;
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
  color: var(--ly-toast-text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.chat-toast__time {
  flex-shrink: 0;
  font-size: $font-size-xs;
  color: var(--ly-toast-muted);
  font-variant-numeric: tabular-nums;
}

.chat-toast__preview {
  margin: 0;
  font-size: 0.8125rem;
  line-height: 1.45;
  color: var(--ly-toast-secondary);
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
