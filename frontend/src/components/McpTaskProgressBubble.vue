<template>
  <div class="mcp-task" role="status" aria-live="polite">
    <div class="mcp-task__avatar" aria-hidden="true">
      <CharacterAvatarImg
        :character-id="task.actor?.characterId"
        :characters="charactersStore.list"
        :avatar-url="stompAvatarFallback.avatarUrl"
        :avatar-thumb-url="stompAvatarFallback.avatarThumbUrl"
        :alt="displayName"
        :icon-size="18"
      />
    </div>
    <div class="mcp-task__bubble">
      <div class="mcp-task__header">
        <span class="mcp-task__name">{{ displayName }}</span>
        <span class="mcp-task__badge"><span class="mcp-task__dot" />{{ t('chat.mcpTaskActive') }}</span>
      </div>
      <transition name="mcp-task-line" mode="out-in">
        <p :key="displayLine" class="mcp-task__line">{{ displayLine }}</p>
      </transition>
    </div>
  </div>
</template>

<script setup>
import { computed, onUnmounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useCharactersStore } from '@/stores/characters'
import CharacterAvatarImg from '@/components/CharacterAvatarImg.vue'
import { sameCharacterId } from '@/utils/characterAvatar'

// 进度行显示限频：引擎一轮只要 1~3s，逐条翻会显得抖；合并突发，始终取最新。
const LINE_MIN_INTERVAL_MS = 1500

const props = defineProps({
  task: { type: Object, required: true },
})

const { t } = useI18n()
const charactersStore = useCharactersStore()

const displayName = computed(() =>
  props.task?.actor?.name || t('about.mcpControlFallbackName'),
)

const listCharacter = computed(() => {
  const id = props.task?.actor?.characterId
  if (id == null) return null
  return (charactersStore.list || []).find((c) => c != null && sameCharacterId(c.id ?? c.characterId, id)) || null
})

// STOMP 常带未解析的 object key；本地列表有公开头像时不要让它盖过缩略图。
const stompAvatarFallback = computed(() => {
  const local = listCharacter.value
  if (local?.avatarUrl || local?.avatarThumbUrl) {
    return { avatarUrl: '', avatarThumbUrl: '' }
  }
  return {
    avatarUrl: props.task?.actor?.avatarUrl || '',
    avatarThumbUrl: props.task?.actor?.avatarThumbUrl || '',
  }
})

const latestLine = computed(() => {
  const updates = props.task?.updates
  if (Array.isArray(updates) && updates.length) {
    return updates[updates.length - 1].text
  }
  const instruction = String(props.task?.instruction || '').trim()
  if (instruction) {
    return instruction.length > 40 ? `${instruction.slice(0, 40)}…` : instruction
  }
  return t('chat.mcpTaskStarting')
})

const displayLine = ref(latestLine.value)
let lastShownAt = 0
let pendingTimer = null

watch(latestLine, (line) => {
  const elapsed = Date.now() - lastShownAt
  if (elapsed >= LINE_MIN_INTERVAL_MS) {
    lastShownAt = Date.now()
    displayLine.value = line
    return
  }
  clearTimeout(pendingTimer)
  pendingTimer = setTimeout(() => {
    lastShownAt = Date.now()
    displayLine.value = latestLine.value
  }, LINE_MIN_INTERVAL_MS - elapsed)
})

onUnmounted(() => clearTimeout(pendingTimer))
</script>

<style lang="scss" scoped>
.mcp-task {
  display: flex;
  align-items: flex-start;
  gap: 0.5rem;
  padding: 0 0.25rem;
  margin: 0.25rem 0 0.5rem;

  &__avatar {
    width: 2rem;
    height: 2rem;
    border-radius: 9999px;
    overflow: hidden;
    flex-shrink: 0;
    display: flex;
    align-items: center;
    justify-content: center;
    background: var(--ly-chat-hero-bubble-bg);
    border: 1px solid var(--ly-chat-hero-bubble-border);
    color: var(--ly-text-muted);

    :deep(img),
    img {
      width: 100%;
      height: 100%;
      object-fit: cover;
    }
  }

  &__bubble {
    max-width: min(26rem, 82%);
    padding: 0.625rem 0.875rem;
    border-radius: 24px;
    border-top-left-radius: 8px;
    background: var(--ly-bg-glass);
    border: 1px solid rgba(var(--ly-accent-rgb), 0.14);
    backdrop-filter: blur(20px) saturate(120%);
    -webkit-backdrop-filter: blur(20px) saturate(120%);
    box-shadow: 0 0 30px rgba(var(--ly-accent-rgb), 0.08);
  }

  &__header {
    display: flex;
    align-items: center;
    gap: 0.5rem;
    margin-bottom: 0.125rem;
  }

  &__name {
    font-size: 0.75rem;
    font-weight: 500;
    color: var(--ly-text-secondary);
  }

  &__badge {
    display: inline-flex;
    align-items: center;
    gap: 0.25rem;
    font-size: 0.75rem;
    font-weight: 500;
    color: var(--ly-accent);
  }

  &__dot {
    width: 6px;
    height: 6px;
    border-radius: 9999px;
    background: var(--ly-accent);
    animation: mcp-task-pulse 1.2s cubic-bezier(0.23, 1, 0.32, 1) infinite;
  }

  &__line {
    margin: 0;
    font-size: 0.875rem;
    line-height: 1.6;
    color: var(--ly-text-primary);
    word-break: break-word;
  }
}

@keyframes mcp-task-pulse {
  0%, 100% { opacity: 1; transform: scale(1); }
  50% { opacity: 0.45; transform: scale(0.8); }
}

.mcp-task-line-enter-active,
.mcp-task-line-leave-active {
  transition: opacity 0.22s cubic-bezier(0.23, 1, 0.32, 1),
    transform 0.22s cubic-bezier(0.23, 1, 0.32, 1);
}

.mcp-task-line-enter-from {
  opacity: 0;
  transform: translateY(4px);
}

.mcp-task-line-leave-to {
  opacity: 0;
  transform: translateY(-4px);
}

@media (prefers-reduced-motion: reduce) {
  .mcp-task__dot {
    animation: none;
  }
  .mcp-task-line-enter-active,
  .mcp-task-line-leave-active {
    transition: none;
  }
}
</style>
