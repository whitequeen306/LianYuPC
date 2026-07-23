<template>
  <div class="chat-share-snapshot" :class="{ 'is-light': theme === 'light' }">
    <header v-if="title" class="chat-share-snapshot__head">
      <span class="chat-share-snapshot__eyebrow">Chat Share</span>
      <h2 class="chat-share-snapshot__title">{{ title }}</h2>
    </header>

    <div class="chat-share-snapshot__log">
      <div
        v-for="item in timeline"
        :key="item.id"
        class="chat-share-snapshot__row"
        :class="item.role === 'user' ? 'is-user' : 'is-hero'"
      >
        <template v-if="item.role !== 'user'">
          <div v-if="item._firstOfGroup" class="chat-share-snapshot__avatar chat-share-snapshot__avatar--hero">
            <img
              v-if="heroAvatarSrc"
              :src="heroAvatarSrc"
              alt=""
              crossorigin="anonymous"
            />
            <span v-else class="chat-share-snapshot__avatar-fallback">{{ heroInitial }}</span>
          </div>
          <div v-else class="chat-share-snapshot__avatar-spacer" aria-hidden="true" />
        </template>

        <div
          class="chat-share-snapshot__bubble"
          :class="item.role === 'user' ? 'is-user' : 'is-hero'"
        >
          <span v-if="item.role !== 'user' && item._firstOfGroup" class="chat-share-snapshot__name">
            {{ characterName }}
          </span>
          <p v-if="item.imageLabel" class="chat-share-snapshot__text">{{ item.imageLabel }}</p>
          <p v-else-if="item.audioLabel" class="chat-share-snapshot__text">{{ item.audioLabel }}</p>
          <p v-else-if="item.content" class="chat-share-snapshot__text">{{ item.content }}</p>
        </div>

        <template v-if="item.role === 'user'">
          <div v-if="item._firstOfGroup" class="chat-share-snapshot__avatar chat-share-snapshot__avatar--user">
            <img
              v-if="userAvatarSrc"
              :src="userAvatarSrc"
              alt=""
              crossorigin="anonymous"
            />
            <span v-else class="chat-share-snapshot__avatar-fallback">{{ userInitial }}</span>
          </div>
          <div v-else class="chat-share-snapshot__avatar-spacer" aria-hidden="true" />
        </template>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { resolveMediaUrl } from '@/utils/media'
import { buildChatShareTimeline } from '@/utils/communityShareDraft'

const props = defineProps({
  messages: { type: Array, default: () => [] },
  characterName: { type: String, default: '角色' },
  characterAvatarUrl: { type: String, default: '' },
  userLabel: { type: String, default: '我' },
  userAvatarUrl: { type: String, default: '' },
  title: { type: String, default: '对话分享' },
  theme: { type: String, default: 'dark' }
})

const timeline = computed(() => buildChatShareTimeline(props.messages, {
  characterName: props.characterName,
  userLabel: props.userLabel
}))

const heroAvatarSrc = computed(() =>
  props.characterAvatarUrl ? resolveMediaUrl(props.characterAvatarUrl) : ''
)
const userAvatarSrc = computed(() =>
  props.userAvatarUrl ? resolveMediaUrl(props.userAvatarUrl) : ''
)
const heroInitial = computed(() => (props.characterName || '角').slice(0, 1))
const userInitial = computed(() => (props.userLabel || '我').slice(0, 1))
</script>

<style lang="scss" scoped>
.chat-share-snapshot {
  width: 420px;
  padding: $space-5;
  border-radius: $radius-lg;
  background: var(--ly-chat-scene-bg, #0a0a12);
  border: 1px solid color-mix(in srgb, var(--ly-accent) 16%, transparent);
  box-shadow: 0 18px 48px rgba(0, 0, 0, 0.28);
  color: var(--ly-text-primary);
  font-family: inherit;
}

.chat-share-snapshot__head {
  margin-bottom: $space-4;
  padding-bottom: $space-3;
  border-bottom: 1px solid color-mix(in srgb, var(--ly-accent) 12%, transparent);
}

.chat-share-snapshot__eyebrow {
  display: block;
  font-size: $font-size-xs;
  letter-spacing: 0.16em;
  color: var(--ly-accent);
  opacity: 0.9;
  margin-bottom: $space-1;
}

.chat-share-snapshot__title {
  margin: 0;
  font-size: $font-size-lg;
  font-weight: $font-weight-semibold;
  line-height: 1.3;
}

.chat-share-snapshot__log {
  display: flex;
  flex-direction: column;
  gap: $space-3;
}

.chat-share-snapshot__row {
  display: flex;
  align-items: flex-end;
  gap: $space-2;

  &.is-user {
    justify-content: flex-end;
  }

  &.is-hero {
    justify-content: flex-start;
  }
}

.chat-share-snapshot__avatar,
.chat-share-snapshot__avatar-spacer {
  width: 36px;
  height: 36px;
  flex-shrink: 0;
}

.chat-share-snapshot__avatar {
  border-radius: $radius-full;
  overflow: hidden;
  display: grid;
  place-items: center;
  background: color-mix(in srgb, var(--ly-accent) 12%, transparent);
  border: 1px solid color-mix(in srgb, var(--ly-accent) 20%, transparent);
  color: var(--ly-accent);

  img {
    width: 100%;
    height: 100%;
    object-fit: cover;
  }
}

.chat-share-snapshot__avatar-fallback {
  font-size: $font-size-sm;
  font-weight: $font-weight-semibold;
}

.chat-share-snapshot__bubble {
  max-width: 72%;
  padding: $space-3 $space-4;
  border-radius: $radius-md;
  min-width: 0;

  &.is-hero {
    background: var(--ly-chat-hero-bubble-bg);
    border: 1px solid var(--ly-chat-hero-bubble-border);
    border-bottom-left-radius: $radius-sm;
    box-shadow: var(--ly-chat-hero-bubble-shadow);
  }

  &.is-user {
    background: var(--ly-chat-user-bubble-bg);
    border: 1px solid var(--ly-chat-user-bubble-border);
    border-bottom-right-radius: $radius-sm;
    box-shadow: 0 2px 12px rgba($color-pink-rgb, 0.16);
  }
}

.chat-share-snapshot__name {
  display: block;
  margin-bottom: 4px;
  font-size: $font-size-xs;
  font-weight: $font-weight-semibold;
  color: var(--ly-accent);
}

.chat-share-snapshot__text {
  margin: 0;
  font-size: $font-size-base;
  line-height: $line-height-normal;
  white-space: pre-wrap;
  word-break: break-word;
  color: inherit;
}

.chat-share-snapshot__bubble.is-hero .chat-share-snapshot__text {
  color: var(--ly-chat-hero-bubble-text);
}

.chat-share-snapshot__bubble.is-user .chat-share-snapshot__text {
  color: var(--ly-chat-user-bubble-text);
}
</style>
