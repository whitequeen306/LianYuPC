<template>
  <div class="voice-wrap">
    <button
      type="button"
      class="voice-bubble"
      :class="{
        'voice-bubble--playing': playing,
        'voice-bubble--user': variant === 'user',
        'voice-bubble--hero': variant !== 'user',
      }"
      :aria-label="playing ? '停止播放语音' : '播放语音'"
      :title="transcript ? '左键播放，右键转文字' : undefined"
      @click="toggle"
      @contextmenu.prevent="onContextMenu"
    >
      <span class="voice-bubble__icon" aria-hidden="true">
        <span class="voice-bubble__bar" />
        <span class="voice-bubble__bar" />
        <span class="voice-bubble__bar" />
      </span>
      <span class="voice-bubble__duration">{{ displayDuration }}</span>
    </button>

    <div
      v-if="showTranscript && transcriptText"
      class="voice-transcript"
      role="dialog"
      aria-label="语音转文字"
    >
      <div class="voice-transcript__head">
        <span>转文字</span>
        <button type="button" class="voice-transcript__close" aria-label="关闭" @click="hideTranscript">×</button>
      </div>
      <p class="voice-transcript__body">{{ transcriptText }}</p>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { resolveMediaUrl, resolveStaticAsset } from '@/utils/media.js'
import { applyPetVoiceGain } from '@/utils/petVoiceGain.js'

const props = defineProps({
  audioUrl: { type: String, required: true },
  /** optional fallback seconds shown before metadata loads */
  durationHint: { type: Number, default: 0 },
  /** message text stored with the voice clip — shown on right-click */
  transcript: { type: String, default: '' },
  variant: { type: String, default: 'hero' },
  playbackRate: { type: Number, default: 1 },
  /** Absolute loudness multiplier; >1 boosts via Web Audio gain */
  volumeGain: { type: Number, default: 0.92 },
})

const playing = ref(false)
const durationSec = ref(0)
const showTranscript = ref(false)
let audio = null
let probeAudio = null

const src = computed(() => resolveMessageAudioUrl(props.audioUrl))
const transcriptText = computed(() => String(props.transcript || '').trim())

const displayDuration = computed(() => {
  const sec = durationSec.value > 0
    ? Math.round(durationSec.value)
    : (Number.isFinite(props.durationHint) && props.durationHint > 0
      ? Math.round(props.durationHint)
      : estimateDurationFromText(transcriptText.value))
  return `${Math.max(1, sec || 1)}″`
})

function estimateDurationFromText(text) {
  const n = String(text || '').replace(/\s/g, '').length
  if (n <= 0) return 0
  // ~3.2 Chinese chars/sec for conversational TTS
  return Math.max(1, Math.round(n / 3.2))
}

function resolveMessageAudioUrl(url) {
  if (!url) return ''
  const trimmed = String(url).trim()
  if (/^https?:\/\//i.test(trimmed) || trimmed.startsWith('data:')) return trimmed
  if (trimmed.startsWith('pet/')) return resolveStaticAsset(trimmed)
  return resolveMediaUrl(trimmed)
}

function stop() {
  if (audio) {
    try {
      audio.pause()
      audio.currentTime = 0
    } catch {
      // ignore
    }
  }
  audio = null
  playing.value = false
  revokeBlobUrl()
}

let blobUrl = null

function revokeBlobUrl() {
  if (!blobUrl) return
  try {
    URL.revokeObjectURL(blobUrl)
  } catch {
    // ignore
  }
  blobUrl = null
}

function applyPlaybackRate(el) {
  const rate = Number.isFinite(props.playbackRate) && props.playbackRate > 0
    ? Math.min(props.playbackRate, 1.1)
    : 1
  el.playbackRate = rate
}

function applyDurationFrom(el) {
  if (!el) return
  const d = el.duration
  if (Number.isFinite(d) && d > 0 && d !== Infinity) {
    durationSec.value = d
  }
}

function bindAudioEvents(el) {
  el.onloadedmetadata = () => applyDurationFrom(el)
  el.ondurationchange = () => applyDurationFrom(el)
  el.onended = () => {
    playing.value = false
    audio = null
    revokeBlobUrl()
  }
  el.onerror = () => {
    playing.value = false
    audio = null
    revokeBlobUrl()
  }
}

function stopProbe() {
  if (!probeAudio) return
  try {
    probeAudio.removeAttribute('src')
    probeAudio.load()
  } catch {
    // ignore
  }
  probeAudio = null
}

/** Load metadata without playing so duration shows before first click. */
async function probeDuration(url) {
  if (!url) return
  stopProbe()
  try {
    const probe = new Audio()
    probe.preload = 'metadata'
    probeAudio = probe
    await new Promise((resolve) => {
      let settled = false
      const done = () => {
        if (settled) return
        settled = true
        applyDurationFrom(probe)
        resolve()
      }
      probe.onloadedmetadata = done
      probe.ondurationchange = done
      probe.onerror = done
      probe.src = url
      // Some Electron builds need an explicit load() for local/static assets.
      try { probe.load() } catch { /* ignore */ }
      setTimeout(done, 2500)
    })
    if (durationSec.value > 0) return

    // Fallback: fetch→blob then read metadata (CORS-gated remote audio).
    if (url.includes('/pet/voice/') || url.includes('pet/voice/') || url.startsWith('blob:') || url.startsWith('file:')) {
      return
    }
    try {
      const playUrl = await resolvePlayableUrl(url)
      const probe2 = new Audio()
      probe2.preload = 'metadata'
      probeAudio = probe2
      await new Promise((resolve) => {
        let settled = false
        const done = () => {
          if (settled) return
          settled = true
          applyDurationFrom(probe2)
          resolve()
        }
        probe2.onloadedmetadata = done
        probe2.onerror = done
        probe2.src = playUrl
        try { probe2.load() } catch { /* ignore */ }
        setTimeout(done, 2500)
      })
    } catch {
      // keep text estimate / hint
    }
  } catch {
    // ignore
  }
}

async function playFrom(url) {
  audio = new Audio(url)
  applyPetVoiceGain(audio, props.volumeGain)
  applyPlaybackRate(audio)
  bindAudioEvents(audio)
  await audio.play()
  applyDurationFrom(audio)
}

async function toggle() {
  if (playing.value) {
    stop()
    return
  }
  if (!src.value) return
  stop()
  playing.value = true
  try {
    await playFrom(src.value)
  } catch {
    try {
      const playUrl = await resolvePlayableUrl(src.value)
      await playFrom(playUrl)
    } catch {
      playing.value = false
      audio = null
      revokeBlobUrl()
    }
  }
}

async function resolvePlayableUrl(url) {
  if (!url) return ''
  if (url.startsWith('blob:') || url.startsWith('data:') || url.startsWith('file:')) {
    return url
  }
  if (url.includes('/pet/voice/') || url.includes('pet/voice/')) {
    return url
  }
  const resp = await fetch(url, { credentials: 'omit', mode: 'cors' })
  if (!resp.ok) throw new Error(`audio fetch ${resp.status}`)
  const blob = await resp.blob()
  if (!blob || blob.size < 16) throw new Error('empty audio blob')
  revokeBlobUrl()
  blobUrl = URL.createObjectURL(blob)
  return blobUrl
}

function onContextMenu() {
  if (!transcriptText.value) return
  showTranscript.value = true
}

function hideTranscript() {
  showTranscript.value = false
}

function onDocPointerDown(e) {
  if (!showTranscript.value) return
  const wrap = e.target?.closest?.('.voice-wrap')
  if (!wrap) hideTranscript()
}

watch(() => props.audioUrl, () => {
  stop()
  durationSec.value = 0
  showTranscript.value = false
  void probeDuration(src.value)
})

watch(src, (url) => {
  void probeDuration(url)
})

onMounted(() => {
  document.addEventListener('pointerdown', onDocPointerDown, true)
  void probeDuration(src.value)
})

onUnmounted(() => {
  document.removeEventListener('pointerdown', onDocPointerDown, true)
  stopProbe()
  stop()
})
</script>

<style scoped lang="scss">
.voice-wrap {
  position: relative;
  display: inline-flex;
  flex-direction: column;
  align-items: flex-start;
  max-width: min(280px, 100%);
}

.voice-bubble {
  display: inline-flex;
  align-items: center;
  gap: $space-2;
  min-width: 88px;
  max-width: 200px;
  padding: 10px 14px;
  border: 1px solid rgba($color-pink-rgb, 0.22);
  border-radius: $radius-pill;
  background: rgba($color-pink-rgb, 0.12);
  color: $color-text-primary;
  cursor: pointer;
  transition: background 0.22s cubic-bezier(0.23, 1, 0.32, 1),
    border-color 0.22s cubic-bezier(0.23, 1, 0.32, 1),
    transform 0.22s cubic-bezier(0.23, 1, 0.32, 1);

  &:hover {
    background: rgba($color-pink-rgb, 0.18);
    border-color: rgba($color-pink-rgb, 0.4);
  }

  &:active {
    transform: scale(0.98);
  }

  &--playing {
    background: rgba($color-pink-rgb, 0.22);
    border-color: rgba($color-pink-rgb, 0.55);
  }

  &--user {
    background: rgba($color-pink-rgb, 0.26);
  }
}

.voice-bubble__icon {
  display: inline-flex;
  align-items: flex-end;
  gap: 2px;
  height: 14px;
}

.voice-bubble__bar {
  width: 3px;
  border-radius: $radius-full;
  background: $color-pink-primary;
  height: 6px;

  &:nth-child(2) { height: 12px; }
  &:nth-child(3) { height: 8px; }

  .voice-bubble--playing & {
    animation: voice-bar-pulse 0.9s cubic-bezier(0.23, 1, 0.32, 1) infinite;

    &:nth-child(2) { animation-delay: 0.12s; }
    &:nth-child(3) { animation-delay: 0.24s; }
  }
}

.voice-bubble__duration {
  font-size: 0.8125rem;
  font-variant-numeric: tabular-nums;
  color: $color-text-secondary;
  min-width: 1.75em;
}

.voice-transcript {
  margin-top: $space-2;
  width: 100%;
  min-width: 160px;
  padding: $space-3;
  border-radius: $radius-md;
  background: var(--ly-bg-glass-strong, var(--ly-bg-surface));
  border: 1px solid rgba($color-pink-rgb, 0.2);
  box-sizing: border-box;
}

.voice-transcript__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: $space-2;
  margin-bottom: $space-2;
  font-size: $font-size-xs;
  font-weight: 600;
  color: var(--ly-text-secondary);
}

.voice-transcript__close {
  border: none;
  background: transparent;
  color: var(--ly-text-muted);
  font-size: 1.1rem;
  line-height: 1;
  cursor: pointer;
  padding: 0 2px;

  &:hover {
    color: var(--ly-text-primary);
  }
}

.voice-transcript__body {
  margin: 0;
  font-size: 0.875rem;
  line-height: 1.55;
  color: var(--ly-text-primary);
  word-break: break-word;
  white-space: pre-wrap;
}

@keyframes voice-bar-pulse {
  0%, 100% { transform: scaleY(0.55); opacity: 0.7; }
  50% { transform: scaleY(1); opacity: 1; }
}
</style>
