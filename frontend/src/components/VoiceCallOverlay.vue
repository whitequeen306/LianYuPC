<template>
  <Teleport to="body">
    <div v-if="visible" class="voice-call" role="dialog" aria-modal="true" :aria-label="`${characterName} 语音通话`">
      <div class="voice-call__backdrop" />
      <div class="voice-call__panel glass-strong">
        <header class="voice-call__head">
          <button type="button" class="voice-call__back" @click="goBack">返回</button>
          <div class="voice-call__meta">
            <h2 class="voice-call__name">{{ characterName }}</h2>
            <p class="voice-call__status">{{ statusLabel }}</p>
            <p v-if="live" class="voice-call__timer" aria-live="polite">{{ elapsedLabel }}</p>
          </div>
          <button
            v-if="live"
            type="button"
            class="voice-call__hangup"
            :disabled="ending"
            @click="hangup"
          >
            挂断
          </button>
          <span v-else class="voice-call__head-spacer" aria-hidden="true" />
        </header>

        <div class="voice-call__stage">
          <div class="voice-call__avatars">
            <div
              class="voice-call__avatar-wrap voice-call__avatar-wrap--char"
              :class="{
                'is-live': live,
                'is-speaking': characterSpeaking,
              }"
            >
              <span
                class="voice-call__ripple"
                :style="{ '--voice-level': String(characterSpeaking ? 0.65 : 0) }"
                aria-hidden="true"
              />
              <img
                v-if="avatarUrl"
                :src="avatarUrl"
                class="voice-call__avatar"
                :alt="characterName"
              />
              <div v-else class="voice-call__avatar voice-call__avatar--placeholder" aria-hidden="true" />
            </div>

            <div
              class="voice-call__avatar-wrap voice-call__avatar-wrap--user"
              :class="{
                'is-live': live,
                'is-speaking': userSpeakingVisual,
              }"
            >
              <span
                class="voice-call__ripple"
                :style="{ '--voice-level': String(audioLevel) }"
                aria-hidden="true"
              />
              <img
                v-if="userAvatarUrl"
                :src="userAvatarUrl"
                class="voice-call__avatar"
                alt="我"
              />
              <div v-else class="voice-call__avatar voice-call__avatar--placeholder" aria-hidden="true" />
            </div>
          </div>

          <p
            v-if="displayCaption"
            class="voice-call__caption"
            :class="captionSpeaker === 'user' ? 'voice-call__caption--user' : 'voice-call__caption--char'"
          >
            <span class="voice-call__caption-label">{{ captionSpeaker === 'user' ? '你' : characterName }}</span>
            {{ displayCaption }}
          </p>
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
          <p v-else class="voice-call__live-hint">通话中 · 直接说话即可，可打断对方</p>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<script setup>
import { computed, onUnmounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { useVoiceRecorder } from '@/composables/useVoiceRecorder'
import { voiceCallTurn, voiceCallEnd } from '@/api/voiceCall'
import { applyPetVoiceGain } from '@/utils/petVoiceGain'
import { getPetVoiceRate, getPetVoiceVolume } from '@/constants/petCatalog'
import { humanizeError } from '@/utils/errorMessage'
import { pickVoiceCallPayload, typewriteText } from '@/utils/voiceResponse'
import { resolveMediaUrl } from '@/utils/media'
import { useUserStore } from '@/stores/user'

const props = defineProps({
  visible: { type: Boolean, default: false },
  conversationId: { type: Number, required: true },
  characterName: { type: String, default: '' },
  avatarUrl: { type: String, default: '' },
  voicePetId: { type: String, default: 'raiden' },
})

const emit = defineEmits(['hangup', 'turnComplete', 'callEnded'])

const userStore = useUserStore()
const {
  recording,
  startChunked,
  stopChunked,
  cancel,
  audioLevel,
  speaking,
} = useVoiceRecorder()

const live = ref(false)
const busy = ref(false)
const ending = ref(false)
const phase = ref('idle')
const displayCaption = ref('')
const captionSpeaker = ref('char')
const elapsedSeconds = ref(0)
let audioEl = null
let turnGeneration = 0
let captionEpoch = 0
let listenLoopPromise = null
let turnAbort = null
let elapsedTimer = null
let callStartedAt = 0
/** @type {Array<{ userText: string, replyText: string }>} */
const sessionTurns = []

const BARGE_IN_LEVEL = 0.14

const userAvatarUrl = computed(() => (
  userStore.avatarUrl ? resolveMediaUrl(userStore.avatarUrl) : ''
))

const characterSpeaking = computed(() => phase.value === 'speaking')
const userSpeakingVisual = computed(() => (
  live.value && (speaking.value || phase.value === 'transcribing')
))

const elapsedLabel = computed(() => formatElapsed(elapsedSeconds.value))

const statusLabel = computed(() => {
  if (!live.value) return '点击开始，建立持续语音通话'
  if (phase.value === 'speaking') return `${props.characterName || '角色'} 说话中（可打断）`
  if (phase.value === 'thinking') return '思考中…'
  if (phase.value === 'transcribing') return '识别中…'
  if (recording.value && speaking.value) return '已听到你的声音…'
  if (recording.value) return '正在听…'
  return '通话中'
})

function formatElapsed(totalSec) {
  const sec = Math.max(0, Math.floor(totalSec || 0))
  const m = Math.floor(sec / 60)
  const s = sec % 60
  return `${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`
}

function startElapsedTimer() {
  stopElapsedTimer()
  callStartedAt = Date.now()
  elapsedSeconds.value = 0
  elapsedTimer = setInterval(() => {
    elapsedSeconds.value = Math.floor((Date.now() - callStartedAt) / 1000)
  }, 1000)
}

function stopElapsedTimer() {
  if (elapsedTimer) {
    clearInterval(elapsedTimer)
    elapsedTimer = null
  }
}

function isAbortError(err) {
  return err?.name === 'CanceledError'
    || err?.name === 'AbortError'
    || /abort|cancel/i.test(String(err?.message || ''))
}

function stopPlayback() {
  if (!audioEl) return
  try {
    audioEl.pause()
    audioEl.currentTime = 0
  } catch { /* ignore */ }
  audioEl = null
}

function abortInFlightTurn() {
  if (turnAbort) {
    try { turnAbort.abort() } catch { /* ignore */ }
    turnAbort = null
  }
  stopPlayback()
  captionEpoch += 1
}

async function revealCaption(speaker, text, generation) {
  const epoch = ++captionEpoch
  captionSpeaker.value = speaker
  displayCaption.value = ''
  await typewriteText(text, {
    charDelayMs: 24,
    onUpdate: (partial) => {
      if (epoch !== captionEpoch || generation !== turnGeneration) return
      displayCaption.value = partial
    },
  })
  if (epoch === captionEpoch && generation === turnGeneration) {
    displayCaption.value = text
  }
}

async function playReply(base64, mimeType, generation) {
  stopPlayback()
  if (!live.value || generation !== turnGeneration) return
  const src = `data:${mimeType || 'audio/wav'};base64,${base64}`
  audioEl = new Audio(src)
  applyPetVoiceGain(audioEl, getPetVoiceVolume(props.voicePetId))
  const rate = getPetVoiceRate(props.voicePetId)
  audioEl.playbackRate = Number.isFinite(rate) && rate > 0 ? Math.min(rate, 1.1) : 1
  phase.value = 'speaking'
  try {
    await audioEl.play()
  } catch {
    return
  }
  await new Promise((resolve) => {
    if (!audioEl || generation !== turnGeneration) {
      resolve()
      return
    }
    audioEl.onended = resolve
    audioEl.onerror = resolve
  })
  if (generation === turnGeneration && phase.value === 'speaking') {
    phase.value = 'idle'
  }
}

/**
 * Mic loop runs in parallel (awaitChunk:false). Each chunk may:
 * - be ignored (silence / echo while TTS)
 * - barge-in and cancel current TTS/API turn
 * - start a new ASR→LLM→TTS turn
 */
async function handleChunk(blob) {
  if (!live.value || !blob || blob.size < 16) return

  const playing = phase.value === 'speaking' && !!audioEl
  const inTurn = phase.value === 'thinking' || phase.value === 'transcribing'
  if (playing || inTurn) {
    // 角色播报 / 思考中：只有检测到明显人声才打断，避免扬声器回灌误触发
    if (!(speaking.value || audioLevel.value >= BARGE_IN_LEVEL)) return
    abortInFlightTurn()
  } else if (busy.value) {
    return
  } else if (!(speaking.value || audioLevel.value >= 0.05)) {
    // 空闲静音片段不打 ASR
    return
  }

  const generation = ++turnGeneration
  turnAbort = typeof AbortController !== 'undefined' ? new AbortController() : null
  const signal = turnAbort?.signal
  busy.value = true
  phase.value = 'transcribing'
  try {
    phase.value = 'thinking'
    const data = pickVoiceCallPayload(
      await voiceCallTurn(props.conversationId, blob, 'voice.webm', { signal }),
    )
    if (generation !== turnGeneration || !live.value) return
    if (!data.replyText) {
      phase.value = 'idle'
      return
    }
    if (data.userText) {
      await revealCaption('user', data.userText, generation)
    }
    if (generation !== turnGeneration || !live.value) return
    sessionTurns.push({
      userText: data.userText || '',
      replyText: data.replyText || '',
    })
    emit('turnComplete', data)
    const replyReveal = revealCaption('char', data.replyText, generation)
    if (data.audioBase64) {
      await playReply(data.audioBase64, data.audioMimeType, generation)
    } else {
      phase.value = 'speaking'
      await replyReveal
      if (generation === turnGeneration) phase.value = 'idle'
    }
    await replyReveal
  } catch (err) {
    if (isAbortError(err) || generation !== turnGeneration) return
    ElMessage.error(humanizeError(err, '语音通话失败，请稍后再试'))
  } finally {
    if (generation === turnGeneration) {
      busy.value = false
      if (phase.value !== 'speaking') phase.value = 'idle'
      turnAbort = null
    }
  }
}

async function ensureListening() {
  if (listenLoopPromise) return
  listenLoopPromise = startChunked({
    intervalMs: 1800,
    awaitChunk: false,
    onChunk: handleChunk,
  }).finally(() => {
    listenLoopPromise = null
  })
  // 不 await 整段循环：循环要一直跑到挂断
  void listenLoopPromise.catch(() => {})
}

async function startCall() {
  if (live.value || busy.value || ending.value) return
  displayCaption.value = ''
  captionSpeaker.value = 'char'
  captionEpoch += 1
  turnGeneration = 0
  sessionTurns.length = 0
  phase.value = 'idle'
  try {
    live.value = true
    startElapsedTimer()
    ElMessage.info('通话已开始，请直接说话，可随时打断')
    await ensureListening()
  } catch {
    live.value = false
    stopElapsedTimer()
    ElMessage.error('无法访问麦克风')
  }
}

async function finalizeCallAndClose({ withSummary }) {
  if (ending.value) return
  ending.value = true
  const wasLive = live.value
  const durationSeconds = wasLive
    ? Math.max(elapsedSeconds.value, callStartedAt ? Math.floor((Date.now() - callStartedAt) / 1000) : 0)
    : 0
  const turns = sessionTurns.slice(-40)
  live.value = false
  busy.value = false
  phase.value = 'idle'
  turnGeneration += 1
  abortInFlightTurn()
  stopElapsedTimer()
  await stopChunked()
  cancel()

  let summaryMsg = null
  if (withSummary && wasLive) {
    try {
      summaryMsg = await voiceCallEnd(props.conversationId, { durationSeconds, turns })
    } catch (err) {
      ElMessage.error(humanizeError(err, '通话记录保存失败'))
    }
  }
  ending.value = false
  elapsedSeconds.value = 0
  sessionTurns.length = 0
  if (summaryMsg) {
    emit('callEnded', summaryMsg)
  }
  emit('hangup')
}

async function hangup() {
  await finalizeCallAndClose({ withSummary: true })
}

async function goBack() {
  if (live.value) {
    await finalizeCallAndClose({ withSummary: true })
    return
  }
  emit('hangup')
}

watch(() => props.visible, async (v) => {
  if (!v) {
    live.value = false
    turnGeneration += 1
    abortInFlightTurn()
    stopElapsedTimer()
    await stopChunked()
    cancel()
    ending.value = false
  }
})

onUnmounted(() => {
  live.value = false
  turnGeneration += 1
  abortInFlightTurn()
  stopElapsedTimer()
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

.voice-call__meta {
  flex: 1;
  min-width: 0;
  text-align: center;
}

.voice-call__back,
.voice-call__hangup {
  flex-shrink: 0;
  border: 1px solid rgba($color-pink-rgb, 0.35);
  background: rgba($color-pink-rgb, 0.12);
  color: var(--ly-text-primary);
  border-radius: $radius-pill;
  padding: 8px 16px;
  cursor: pointer;
  transition: background 0.22s cubic-bezier(0.23, 1, 0.32, 1),
    border-color 0.22s cubic-bezier(0.23, 1, 0.32, 1);

  &:hover:not(:disabled) {
    background: rgba($color-pink-rgb, 0.2);
    border-color: rgba($color-pink-rgb, 0.55);
  }

  &:disabled {
    opacity: 0.6;
    cursor: not-allowed;
  }
}

.voice-call__head-spacer {
  width: 64px;
  flex-shrink: 0;
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

.voice-call__timer {
  margin: $space-2 0 0;
  font-variant-numeric: tabular-nums;
  font-size: 1.125rem;
  letter-spacing: 0.06em;
  color: var(--ly-accent);
  font-weight: 600;
}

.voice-call__stage {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: $space-5;
  padding: $space-6 0;
}

.voice-call__avatars {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: $space-5;
}

.voice-call__avatar-wrap {
  position: relative;
  border-radius: $radius-full;
  padding: 4px;
  background: rgba($color-pink-rgb, 0.18);
  transition: box-shadow 0.28s cubic-bezier(0.23, 1, 0.32, 1);

  &--char {
    width: 168px;
    height: 168px;
  }

  &--user {
    width: 88px;
    height: 88px;
  }

  &.is-live {
    box-shadow: 0 0 0 3px rgba($color-pink-rgb, 0.28),
      0 0 22px rgba($color-pink-rgb, 0.18);
  }

  &.is-speaking .voice-call__ripple {
    opacity: 1;
    transform: scale(calc(1.08 + var(--voice-level, 0) * 0.55));
  }
}

.voice-call__ripple {
  position: absolute;
  inset: -10px;
  border-radius: $radius-full;
  border: 2px solid rgba($color-pink-rgb, 0.42);
  box-shadow: 0 0 0 8px rgba($color-pink-rgb, 0.1);
  opacity: 0;
  transform: scale(1);
  pointer-events: none;
  transition: opacity 0.2s cubic-bezier(0.23, 1, 0.32, 1),
    transform 0.2s cubic-bezier(0.23, 1, 0.32, 1);
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
  min-height: 3.2em;

  &--user {
    color: var(--ly-text-secondary);
    background: rgba($color-pink-rgb, 0.06);
  }
}

.voice-call__caption-label {
  display: inline-block;
  margin-right: $space-2;
  color: var(--ly-accent);
  font-weight: 600;
}

.voice-call__controls {
  display: flex;
  justify-content: center;
  padding-bottom: $space-2;
}

.voice-call__live-hint {
  margin: 0;
  font-size: $font-size-sm;
  color: var(--ly-text-secondary);
  text-align: center;
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

  &:disabled {
    opacity: 0.65;
    cursor: not-allowed;
  }
}
</style>
