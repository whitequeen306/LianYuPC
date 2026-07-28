<template>
  <Teleport to="body">
    <div v-if="visible" class="voice-call" role="dialog" aria-modal="true" :aria-label="`${characterName} 语音通话`">
      <div class="voice-call__backdrop" />
      <div class="voice-call__panel glass-strong">
        <header class="voice-call__head">
          <div class="voice-call__meta">
            <h2 class="voice-call__name">{{ characterName }}</h2>
            <p class="voice-call__status">{{ statusLabel }}</p>
          </div>
          <button type="button" class="voice-call__hangup" @click="emit('hangup')">挂断</button>
        </header>

        <div class="voice-call__stage">
          <div class="voice-call__avatar-wrap">
            <img
              v-if="avatarUrl"
              :src="avatarUrl"
              class="voice-call__avatar"
              :alt="characterName"
            />
            <div v-else class="voice-call__avatar voice-call__avatar--placeholder" aria-hidden="true" />
          </div>
          <p v-if="caption" class="voice-call__caption">{{ caption }}</p>
        </div>

        <div class="voice-call__controls">
          <button
            type="button"
            class="voice-call__ptt"
            :class="{ 'is-active': recording || busy }"
            :disabled="busy && !recording"
            @pointerdown.prevent="onPttDown"
            @pointerup.prevent="onPttUp"
            @pointercancel.prevent="onPttCancel"
            @contextmenu.prevent
          >
            {{ pttLabel }}
          </button>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<script setup>
import { computed, onUnmounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { useVoiceRecorder } from '@/composables/useVoiceRecorder'
import { voiceCallTurn } from '@/api/voiceCall'
import { applyPetVoiceGain } from '@/utils/petVoiceGain'
import { getPetVoiceRate, getPetVoiceVolume } from '@/constants/petCatalog'
import { humanizeError } from '@/utils/errorMessage'

const props = defineProps({
  visible: { type: Boolean, default: false },
  conversationId: { type: Number, required: true },
  characterName: { type: String, default: '' },
  avatarUrl: { type: String, default: '' },
  voicePetId: { type: String, default: 'raiden' },
})

const emit = defineEmits(['hangup', 'turnComplete'])

const { recording, start, stop, cancel } = useVoiceRecorder()
const busy = ref(false)
const phase = ref('idle')
const caption = ref('')
let audioEl = null
let pointerDown = false

const statusLabel = computed(() => {
  if (recording.value) return '正在听…'
  switch (phase.value) {
    case 'transcribing': return '识别中…'
    case 'thinking': return '思考中…'
    case 'speaking': return '说话中…'
    default: return '准备就绪，按住说话'
  }
})

const pttLabel = computed(() => {
  if (recording.value) return '松手发送'
  if (busy.value) return '请稍候…'
  return '按住说话'
})

function stopPlayback() {
  if (!audioEl) return
  try {
    audioEl.pause()
    audioEl.currentTime = 0
  } catch { /* ignore */ }
  audioEl = null
}

async function playReply(base64, mimeType) {
  stopPlayback()
  const src = `data:${mimeType || 'audio/wav'};base64,${base64}`
  audioEl = new Audio(src)
  applyPetVoiceGain(audioEl, getPetVoiceVolume(props.voicePetId))
  const rate = getPetVoiceRate(props.voicePetId)
  audioEl.playbackRate = Number.isFinite(rate) && rate > 0 ? Math.min(rate, 1.1) : 1
  phase.value = 'speaking'
  await audioEl.play()
  await new Promise((resolve) => {
    audioEl.onended = resolve
    audioEl.onerror = resolve
  })
}

async function onPttDown() {
  if (busy.value || recording.value) return
  pointerDown = true
  stopPlayback()
  caption.value = ''
  phase.value = 'idle'
  try {
    await start()
  } catch {
    ElMessage.error('无法访问麦克风')
    pointerDown = false
  }
}

async function onPttUp() {
  if (!pointerDown) return
  pointerDown = false
  if (!recording.value) return
  busy.value = true
  phase.value = 'transcribing'
  try {
    const blob = await stop()
    if (!blob || blob.size < 16) {
      ElMessage.warning('录音太短，请再说一次')
      return
    }
    phase.value = 'thinking'
    const res = await voiceCallTurn(props.conversationId, blob)
    const data = res?.data
    if (!data?.replyText) {
      ElMessage.warning('没有收到回复，请重试')
      return
    }
    caption.value = data.replyText
    emit('turnComplete', data)
    if (data.audioBase64) {
      await playReply(data.audioBase64, data.audioMimeType)
    }
  } catch (err) {
    ElMessage.error(humanizeError(err, '语音通话失败，请稍后再试'))
  } finally {
    busy.value = false
    phase.value = 'idle'
  }
}

function onPttCancel() {
  pointerDown = false
  if (recording.value) cancel()
  busy.value = false
  phase.value = 'idle'
}

onUnmounted(() => {
  cancel()
  stopPlayback()
})
</script>

<style scoped lang="scss">
.voice-call {
  position: fixed;
  inset: 0;
  z-index: 5000;
  display: flex;
  align-items: stretch;
  justify-content: center;
}

.voice-call__backdrop {
  position: absolute;
  inset: 0;
  background: rgba(10, 10, 18, 0.72);
  backdrop-filter: blur(12px);
}

.voice-call__panel {
  position: relative;
  z-index: 1;
  width: min(420px, 100%);
  margin: auto;
  min-height: min(640px, 100vh);
  display: flex;
  flex-direction: column;
  padding: $space-5;
  border-radius: $radius-xl;
  background: var(--ly-bg-glass-strong, var(--ly-bg-surface)) !important;
}

.voice-call__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: $space-3;
}

.voice-call__name {
  margin: 0;
  font-family: $font-display-serif;
  font-size: 1.25rem;
  color: var(--ly-text-primary);
}

.voice-call__status {
  margin: $space-1 0 0;
  font-size: $font-size-sm;
  color: var(--ly-text-secondary);
}

.voice-call__hangup {
  border: 1px solid rgba($color-pink-rgb, 0.35);
  background: rgba($color-pink-rgb, 0.12);
  color: var(--ly-text-primary);
  border-radius: $radius-pill;
  padding: 8px 16px;
  cursor: pointer;
  transition: background 0.22s cubic-bezier(0.23, 1, 0.32, 1),
    border-color 0.22s cubic-bezier(0.23, 1, 0.32, 1);

  &:hover {
    background: rgba($color-pink-rgb, 0.2);
    border-color: rgba($color-pink-rgb, 0.55);
  }
}

.voice-call__stage {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: $space-4;
  padding: $space-6 0;
}

.voice-call__avatar-wrap {
  width: 168px;
  height: 168px;
  border-radius: $radius-full;
  padding: 4px;
  background: rgba($color-pink-rgb, 0.18);
}

.voice-call__avatar {
  width: 100%;
  height: 100%;
  border-radius: $radius-full;
  object-fit: cover;
  display: block;
  background: var(--ly-bg-elevated);

  &--placeholder {
    background: linear-gradient(145deg, rgba($color-pink-rgb, 0.25), rgba($color-pink-rgb, 0.08));
  }
}

.voice-call__caption {
  margin: 0;
  max-width: 100%;
  text-align: center;
  font-size: 0.9375rem;
  line-height: 1.55;
  color: var(--ly-text-primary);
  padding: $space-3 $space-4;
  border-radius: $radius-lg;
  background: rgba($color-pink-rgb, 0.1);
  border: 1px solid rgba($color-pink-rgb, 0.18);
}

.voice-call__controls {
  display: flex;
  justify-content: center;
  padding-bottom: $space-2;
}

.voice-call__ptt {
  min-width: 180px;
  padding: 14px 28px;
  border: 1px solid rgba($color-pink-rgb, 0.35);
  border-radius: $radius-pill;
  background: rgba($color-pink-rgb, 0.16);
  color: var(--ly-text-primary);
  font-size: 1rem;
  font-weight: 600;
  cursor: pointer;
  transition: transform 0.22s cubic-bezier(0.23, 1, 0.32, 1),
    background 0.22s cubic-bezier(0.23, 1, 0.32, 1),
    border-color 0.22s cubic-bezier(0.23, 1, 0.32, 1);

  &:hover:not(:disabled) {
    background: rgba($color-pink-rgb, 0.24);
    border-color: rgba($color-pink-rgb, 0.55);
  }

  &.is-active {
    transform: scale(0.98);
    background: rgba($color-pink-rgb, 0.32);
    border-color: rgba($color-pink-rgb, 0.65);
  }

  &:disabled {
    opacity: 0.65;
    cursor: not-allowed;
  }
}
</style>
