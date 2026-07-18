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
  if (!audio) return
  try {
    audio.pause()
    audio.currentTime = 0
  } catch {
    // ignore
  }
  audio = null
  playing.value = false
}

function toggle() {
  if (playing.value) {
    stop()
    return
  }
  if (!src.value) return
  stop()
  audio = new Audio(src.value)
  audio.volume = 0.92
  if (Number.isFinite(props.playbackRate) && props.playbackRate > 0) {
    audio.playbackRate = props.playbackRate
  }
  audio.onloadedmetadata = () => {
    if (Number.isFinite(audio?.duration) && audio.duration > 0) {
      durationSec.value = audio.duration
    }
  }
  audio.onended = () => {
    playing.value = false
    audio = null
  }
  audio.onerror = () => {
    playing.value = false
    audio = null
  }
  playing.value = true
  const p = audio.play()
  if (p && typeof p.catch === 'function') {
    p.catch(() => {
      playing.value = false
      audio = null
    })
  }
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
