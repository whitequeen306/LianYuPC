<template>
  <div class="chat-share-snapshot">
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
  title: { type: String, default: '对话分享' }
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

<!-- html2canvas 不支持 color-mix()/linear-gradient()/CSS 变量，此处只用静态 rgba/hex -->
<style scoped>
.chat-share-snapshot {
  width: 420px;
  padding: 20px;
  border-radius: 24px;
  background: #0a0a12;
  border: 1px solid rgba(244, 166, 181, 0.16);
  box-shadow: 0 18px 48px rgba(0, 0, 0, 0.28);
  color: #e8edf2;
  font-family: 'PingFang SC', 'Microsoft YaHei', system-ui, sans-serif;
}

.chat-share-snapshot__head {
  margin-bottom: 16px;
  padding-bottom: 12px;
  border-bottom: 1px solid rgba(244, 166, 181, 0.12);
}

.chat-share-snapshot__eyebrow {
  display: block;
  font-size: 12px;
  letter-spacing: 0.16em;
  color: #f4a6b5;
  margin-bottom: 4px;
}

.chat-share-snapshot__title {
  margin: 0;
  font-size: 18px;
  font-weight: 600;
  line-height: 1.3;
  color: #e8edf2;
}

.chat-share-snapshot__log {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.chat-share-snapshot__row {
  display: flex;
  align-items: flex-end;
  gap: 8px;
}

.chat-share-snapshot__row.is-user {
  justify-content: flex-end;
}

.chat-share-snapshot__row.is-hero {
  justify-content: flex-start;
}

.chat-share-snapshot__avatar,
.chat-share-snapshot__avatar-spacer {
  width: 36px;
  height: 36px;
  flex-shrink: 0;
}

.chat-share-snapshot__avatar {
  border-radius: 9999px;
  overflow: hidden;
  display: grid;
  place-items: center;
  background: rgba(244, 166, 181, 0.12);
  border: 1px solid rgba(244, 166, 181, 0.2);
  color: #f4a6b5;
}

.chat-share-snapshot__avatar img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.chat-share-snapshot__avatar-fallback {
  font-size: 14px;
  font-weight: 600;
}

.chat-share-snapshot__bubble {
  max-width: 72%;
  padding: 12px 16px;
  border-radius: 14px;
  min-width: 0;
}

.chat-share-snapshot__bubble.is-hero {
  background: rgba(16, 16, 24, 0.72);
  border: 1px solid rgba(255, 255, 255, 0.1);
  border-bottom-left-radius: 8px;
  box-shadow: 0 2px 12px rgba(0, 0, 0, 0.26);
}

.chat-share-snapshot__bubble.is-user {
  background: rgba(244, 166, 181, 0.26);
  border: 1px solid rgba(244, 166, 181, 0.34);
  border-bottom-right-radius: 8px;
  box-shadow: 0 2px 12px rgba(244, 166, 181, 0.16);
}

.chat-share-snapshot__name {
  display: block;
  margin-bottom: 4px;
  font-size: 12px;
  font-weight: 600;
  color: #f8c8d8;
}

.chat-share-snapshot__text {
  margin: 0;
  font-size: 15px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
}

.chat-share-snapshot__bubble.is-hero .chat-share-snapshot__text {
  color: rgba(255, 255, 255, 0.94);
}

.chat-share-snapshot__bubble.is-user .chat-share-snapshot__text {
  color: #e8edf2;
}
</style>
