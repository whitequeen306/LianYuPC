/**
 * Apply playback loudness. HTMLAudioElement.volume tops out at 1;
 * values above 1 use a shared Web Audio GainNode boost (e.g. elysia 1.3x).
 */

let sharedCtx = null
const wired = new WeakMap()

function getSharedContext() {
  if (typeof window === 'undefined') return null
  const Ctor = window.AudioContext || window.webkitAudioContext
  if (!Ctor) return null
  if (!sharedCtx || sharedCtx.state === 'closed') {
    sharedCtx = new Ctor()
  }
  return sharedCtx
}

/**
 * @param {HTMLAudioElement} audio
 * @param {number} multiplier absolute loudness (0.9 default-ish, 1.3 = +30%)
 */
export function applyPetVoiceGain(audio, multiplier) {
  if (!audio) return
  const m = Number.isFinite(multiplier) && multiplier > 0 ? Math.min(2, multiplier) : 0.9

  if (m <= 1) {
    audio.volume = m
    const node = wired.get(audio)
    if (node) node.gain.value = 1
    return
  }

  audio.volume = 1
  const ctx = getSharedContext()
  if (!ctx) return

  let gain = wired.get(audio)
  if (!gain) {
    try {
      const src = ctx.createMediaElementSource(audio)
      gain = ctx.createGain()
      src.connect(gain)
      gain.connect(ctx.destination)
      wired.set(audio, gain)
    } catch {
      // Element already wired elsewhere, or AudioContext unavailable — stay at volume 1.
      return
    }
  }
  gain.gain.value = m
  if (ctx.state === 'suspended') {
    void ctx.resume().catch(() => {})
  }
}
