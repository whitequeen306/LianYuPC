<template>
  <button
    type="button"
    class="voice-bubble"
    :class="{
      'voice-bubble--playing': playing,
      'voice-bubble--user': variant === 'user',
      'voice-bubble--hero': variant !== 'user',
    }"
    :aria-label="playing ? '停止播放语音' : '播放语音'"
    @click="toggle"
  >
    <span class="voice-bubble__icon" aria-hidden="true">
      <span class="voice-bubble__bar" />
      <span class="voice-bubble__bar" />
      <span class="voice-bubble__bar" />
    </span>
    <span class="voice-bubble__duration">{{ displayDuration }}</span>
  </button>
</template>

<script setup>
import { computed, onUnmounted, ref, watch } from 'vue'
import { resolveMediaUrl, resolveStaticAsset } from '@/utils/media.js'

const props = defineProps({
  audioUrl: { type: String, required: true },
  /** optional fallback seconds shown before metadata loads */
  durationHint: { type: Number, default: 0 },
  variant: { type: String, default: 'hero' },
  playbackRate: { type: Number, default: 1 },
})

const playing = ref(false)
const durationSec = ref(0)
let audio = null

const src = computed(() => resolveMessageAudioUrl(props.audioUrl))

const displayDuration = computed(() => {
  const sec = durationSec.value > 0
    ? Math.round(durationSec.value)
    : (Number.isFinite(props.durationHint) && props.durationHint > 0
      ? Math.round(props.durationHint)
      : 1)
  return `${Math.max(1, sec)}″`
})

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

function bindAudioEvents(el) {
  el.onloadedmetadata = () => {
    if (Number.isFinite(el?.duration) && el.duration > 0) {
      durationSec.value = el.duration
    }
  }
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

async function playFrom(url) {
  audio = new Audio(url)
  audio.volume = 0.92
  applyPlaybackRate(audio)
  bindAudioEvents(audio)
  await audio.play()
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
    // Media elements can usually play cross-origin without CORS (unlike fetch).
    await playFrom(src.value)
  } catch {
    try {
      // Electron/file origin: if direct play fails, fetch→blob (needs ACAO on API).
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

watch(() => props.audioUrl, () => stop())

onUnmounted(() => stop())
</script>

<style scoped lang="scss">
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
  transition: background $transition-fast, border-color $transition-fast, transform $transition-fast;

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

@keyframes voice-bar-pulse {
  0%, 100% { transform: scaleY(0.55); opacity: 0.7; }
  50% { transform: scaleY(1); opacity: 1; }
}
</style>
