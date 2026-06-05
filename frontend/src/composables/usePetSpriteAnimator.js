import { onMounted, onUnmounted } from 'vue'
import { PET_ANIMATIONS, PET_FRAME_H, PET_FRAME_W } from '@/constants/petSprite'

/**
 * JS 驱动 spritesheet 逐帧播放，避免 CSS steps 踩空帧/重叠。
 */
export function usePetSpriteAnimator(petRef) {
  let timer = null
  let frame = 0
  let currentName = 'idle'
  let animDef = PET_ANIMATIONS.idle
  let onCompleteCb = null
  let idleVarietyTimer = null

  function clearTimer() {
    if (timer) {
      clearInterval(timer)
      timer = null
    }
  }

  function renderFrame() {
    const el = petRef.value
    if (!el || !animDef) return
    el.style.backgroundPosition = `-${frame * PET_FRAME_W}px -${animDef.row * PET_FRAME_H}px`
  }

  function playAnim(name, { loop, onComplete } = {}) {
    const def = PET_ANIMATIONS[name]
    if (!def) return
    clearTimer()
    currentName = name
    animDef = def
    frame = 0
    onCompleteCb = onComplete || null
    const shouldLoop = loop ?? def.loop
    renderFrame()
    timer = setInterval(() => {
      frame += 1
      if (frame >= def.frames) {
        if (shouldLoop) {
          frame = 0
        } else {
          clearTimer()
          const cb = onCompleteCb
          onCompleteCb = null
          cb?.()
          playAnim('idle')
          return
        }
      }
      renderFrame()
    }, 1000 / def.fps)
  }

  function scheduleIdleVariety() {
    clearTimeout(idleVarietyTimer)
    const delay = 12000 + Math.random() * 18000
    idleVarietyTimer = setTimeout(() => {
      if (currentName === 'idle') {
        playAnim(Math.random() > 0.5 ? 'review' : 'waiting')
      }
      scheduleIdleVariety()
    }, delay)
  }

  onMounted(() => {
    playAnim('idle')
    scheduleIdleVariety()
  })

  onUnmounted(() => {
    clearTimer()
    clearTimeout(idleVarietyTimer)
  })

  return { playAnim }
}
