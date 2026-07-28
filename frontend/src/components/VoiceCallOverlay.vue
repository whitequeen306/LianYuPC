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
          <button type="button" class="voice-call__hangup" @click="hangup">挂断</button>
        </header>

        <div class="voice-call__stage">
          <div class="voice-call__avatar-wrap" :class="{ 'is-live': live }">
            <img
              v-if="avatarUrl"
              :src="avatarUrl"
              class="voice-call__avatar"
              :alt="characterName"
            />
            <div v-else class="voice-call__avatar voice-call__avatar--placeholder" aria-hidden="true" />
          </div>
          <p v-if="userCaption" class="voice-call__caption voice-call__caption--user">你：{{ userCaption }}</p>
          <p v-if="caption" class="voice-call__caption">{{ caption }}</p>
        </div>

        <div class="voice-call__controls">
          <button
            v-if="!live"
            type="button"
            class="voice-call__ptt"
            :disabled="busy"
            @click="startCall"
          >
            开始通话
          </button>
          <button
            v-else
            type="button"
            class="voice-call__ptt is-active"
            :disabled="busy && phase === 'speaking'"
            @click="pauseOrResume"
          >
            {{ listeningPaused ? '继续收听' : '暂停收听' }}
          </button>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<script setup>
import { computed, onUnmounted, ref, watch } from 'vue'
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

const { recording, startChunked, stopChunked, cancel } = useVoiceRecorder()
const live = ref(false)
const listeningPaused = ref(false)
const busy = ref(false)
const phase = ref('idle')
const caption = ref('')
const userCaption = ref('')
let audioEl = null
let turnInFlight = false

const statusLabel = computed(() => {
  if (!live.value) return '点击开始，建立持续语音通话'
  if (listeningPaused.value) return '已暂停收听'
  if (recording.value && phase.value === 'idle') return '正在听…'
  switch (phase.value) {
    case 'transcribing': return '识别中…'
    case 'thinking': return '思考中…'
    case 'speaking': return '说话中…'
    default: return '通话中，持续收听'
  }
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

async function handleChunk(blob) {
  if (!live.value || listeningPaused.value || turnInFlight) return
  if (!blob || blob.size < 16) return
  turnInFlight = true
  busy.value = true
  phase.value = 'transcribing'
  try {
    phase.value = 'thinking'
    const res = await voiceCallTurn(props.conversationId, blob)
    const data = res?.data
    if (!data?.replyText) {
      // 静音/未听清：继续收听，不打断循环
      return
    }
    if (data.userText) userCaption.value = data.userText
    caption.value = data.replyText
    emit('turnComplete', data)
    // 播 TTS 前暂停麦克风，避免回灌
    await stopChunked()
    if (data.audioBase64) {
      await playReply(data.audioBase64, data.audioMimeType)
    }
    if (live.value && !listeningPaused.value) {
      await resumeListening()
    }
  } catch (err) {
    ElMessage.error(humanizeError(err, '语音通话失败，请稍后再试'))
  } finally {
    turnInFlight = false
    busy.value = false
    phase.value = 'idle'
  }
}

async function resumeListening() {
  await startChunked({
    intervalMs: 2800,
    onChunk: handleChunk,
  })
}

async function startCall() {
  if (live.value || busy.value) return
  caption.value = ''
  userCaption.value = ''
  listeningPaused.value = false
  phase.value = 'idle'
  try {
    live.value = true
    ElMessage.info('通话已开始，请直接说话')
    await resumeListening()
  } catch {
    live.value = false
    ElMessage.error('无法访问麦克风')
  }
}

async function pauseOrResume() {
  if (!live.value) return
  if (!listeningPaused.value) {
    listeningPaused.value = true
    await stopChunked()
    phase.value = 'idle'
    return
  }
  listeningPaused.value = false
  try {
    await resumeListening()
  } catch {
    ElMessage.error('无法恢复麦克风')
  }
}

async function hangup() {
  live.value = false
  listeningPaused.value = false
  busy.value = false
  phase.value = 'idle'
  turnInFlight = false
  stopPlayback()
  await stopChunked()
  cancel()
  emit('hangup')
}

watch(() => props.visible, async (v) => {
  if (!v) {
    live.value = false
    listeningPaused.value = false
    stopPlayback()
    await stopChunked()
    cancel()
  }
})

onUnmounted(() => {
  live.value = false
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
  transition: box-shadow 0.28s cubic-bezier(0.23, 1, 0.32, 1);

  &.is-live {
    box-shadow: 0 0 0 3px rgba($color-pink-rgb, 0.35),
      0 0 28px rgba($color-pink-rgb, 0.25);
  }
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

  &--user {
    color: var(--ly-text-secondary);
    background: rgba($color-pink-rgb, 0.06);
  }
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
    background: rgba($color-pink-rgb, 0.32);
    border-color: rgba($color-pink-rgb, 0.65);
  }

  &:disabled {
    opacity: 0.65;
    cursor: not-allowed;
  }
}
</style>
