import {
  getPetFixedVoiceUrl,
  getPetVoiceRate,
  PET_FIXED_VOICE_COOLDOWN_MS,
  petHasInteractiveVoice,
} from '@/constants/petCatalog.js'

let lastPlayAt = 0
let currentAudio = null

/**
 * Play a pre-baked pet interaction clip (click / run).
 * Shared 5s cooldown across kinds; no-op when pet has no VC voice or file missing.
 * @returns {boolean} true if playback was started
 */
export function playPetFixedVoice(petId, kind, { busy = false } = {}) {
  if (busy) return false
  if (!petHasInteractiveVoice(petId)) return false
  if (kind !== 'click' && kind !== 'run') return false

  const now = Date.now()
  if (now - lastPlayAt < PET_FIXED_VOICE_COOLDOWN_MS) return false

  const src = getPetFixedVoiceUrl(petId, kind)
  if (!src) return false

  stopPetFixedVoice()
  try {
    const audio = new Audio(src)
    audio.volume = 0.9
    audio.playbackRate = getPetVoiceRate(petId)
    audio.onended = () => {
      if (currentAudio === audio) currentAudio = null
    }
    audio.onerror = () => {
      if (currentAudio === audio) currentAudio = null
    }
    currentAudio = audio
    lastPlayAt = now
    const playPromise = audio.play()
    if (playPromise && typeof playPromise.catch === 'function') {
      playPromise.catch(() => {
        if (currentAudio === audio) currentAudio = null
      })
    }
    return true
  } catch {
    currentAudio = null
    return false
  }
}

export function stopPetFixedVoice() {
  if (!currentAudio) return
  try {
    currentAudio.pause()
    currentAudio.currentTime = 0
  } catch {
    // ignore
  }
  currentAudio = null
}

/** test helper */
export function __resetPetFixedVoiceForTests() {
  stopPetFixedVoice()
  lastPlayAt = 0
}
