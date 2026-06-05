import { onMounted, onUnmounted } from 'vue'
import { PET_ANIMATIONS, PET_FRAME_H, PET_FRAME_W } from '@/constants/petSprite'

/** 待机时随机小动作（权重越高越常见） */
const IDLE_VARIETY = [
  { name: 'waiting', weight: 3, loop: true },
  { name: 'running', weight: 2, loop: true },
  { name: 'review', weight: 2, loop: false },
  { name: 'wave', weight: 1, loop: false },
  { name: 'jump', weight: 1, loop: false },
  { name: 'failed', weight: 1, loop: false },
]

function pickIdleVariety() {
  const total = IDLE_VARIETY.reduce((sum, item) => sum + item.weight, 0)
  let roll = Math.random() * total
  for (const item of IDLE_VARIETY) {
    roll -= item.weight
    if (roll <= 0) return item
  }
  return IDLE_VARIETY[0]
}

/**
 * Canvas 逐帧绘制 spritesheet，避免 CSS background 在 Electron 透明窗口出现黑底/晕影。
 */
export function usePetSpriteAnimator(canvasRef) {
  let timer = null
  let frame = 0
  let currentName = 'idle'
  let animDef = PET_ANIMATIONS.idle
  let spriteImage = null
  let onCompleteCb = null
  let idleVarietyTimer = null
  let ctx = null

  function ensureCtx() {
    const canvas = canvasRef.value
    if (!canvas) return null
    if (!ctx) {
      ctx = canvas.getContext('2d', { alpha: true, desynchronized: true })
    }
    return ctx
  }

  function clearTimer() {
    if (timer) {
      clearInterval(timer)
      timer = null
    }
  }

  function renderFrame() {
    const canvas = canvasRef.value
    const context = ensureCtx()
    if (!canvas || !context || !animDef || !spriteImage) return
    context.clearRect(0, 0, PET_FRAME_W, PET_FRAME_H)
    context.drawImage(
      spriteImage,
      frame * PET_FRAME_W,
      animDef.row * PET_FRAME_H,
      PET_FRAME_W,
      PET_FRAME_H,
      0,
      0,
      PET_FRAME_W,
      PET_FRAME_H,
    )
  }

  function setSpriteImage(img) {
    spriteImage = img
    renderFrame()
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
          if (currentName === name) {
            playAnim('idle')
          }
          return
        }
      }
      renderFrame()
    }, 1000 / def.fps)
  }

  function playAnimOnce(name, onComplete) {
    playAnim(name, { loop: false, onComplete: onComplete || (() => playAnim('idle')) })
  }

  function returnToIdle() {
    playAnim('idle')
  }

  function scheduleIdleVariety() {
    clearTimeout(idleVarietyTimer)
    const delay = 6000 + Math.random() * 10000
    idleVarietyTimer = setTimeout(() => {
      if (currentName !== 'idle') {
        scheduleIdleVariety()
        return
      }
      const pick = pickIdleVariety()
      playAnim(pick.name, { loop: pick.loop })
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
    ctx = null
  })

  return { playAnim, playAnimOnce, returnToIdle, setSpriteImage }
}
