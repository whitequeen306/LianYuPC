<template>
  <canvas
    ref="canvasRef"
    class="landing-particles"
    aria-hidden="true"
  />
</template>

<script setup>
import { onMounted, onUnmounted, ref } from 'vue'

const canvasRef = ref(null)

const ROSE = { r: 244, g: 166, b: 181 }
const LILAC = { r: 168, g: 148, b: 220 }
const PEARL = { r: 255, g: 240, b: 245 }

let rafId = 0
let particles = []
let width = 0
let height = 0
let dpr = 1
let mouseX = 0.5
let mouseY = 0.5
let reducedMotion = false

function pickPalette() {
  const roll = Math.random()
  if (roll < 0.55) return ROSE
  if (roll < 0.82) return LILAC
  return PEARL
}

function particleCount() {
  const area = width * height
  if (area < 500_000) return 36
  if (area < 900_000) return 52
  return 72
}

function spawnParticle(randomY = false) {
  const palette = pickPalette()
  const depth = 0.35 + Math.random() * 0.65
  return {
    x: Math.random() * width,
    y: randomY ? Math.random() * height : height + Math.random() * 40,
    vx: (Math.random() - 0.5) * 0.22 * depth,
    vy: -(0.12 + Math.random() * 0.38) * depth,
    size: (1.2 + Math.random() * 2.8) * depth,
    glow: 0.35 + Math.random() * 0.65,
    phase: Math.random() * Math.PI * 2,
    twinkle: 0.004 + Math.random() * 0.01,
    palette,
    depth,
  }
}

function initParticles() {
  particles = []
  const n = particleCount()
  for (let i = 0; i < n; i++) {
    const p = spawnParticle(true)
    particles.push(p)
  }
}

function resize() {
  const canvas = canvasRef.value
  if (!canvas) return

  dpr = Math.min(window.devicePixelRatio || 1, 2)
  width = window.innerWidth
  height = window.innerHeight
  canvas.width = Math.floor(width * dpr)
  canvas.height = Math.floor(height * dpr)
  canvas.style.width = `${width}px`
  canvas.style.height = `${height}px`

  const ctx = canvas.getContext('2d')
  if (ctx) ctx.setTransform(dpr, 0, 0, dpr, 0, 0)

  initParticles()
}

function drawParticle(ctx, p, time) {
  const twinkle = 0.45 + 0.55 * Math.sin(time * p.twinkle + p.phase)
  const parallaxX = (mouseX - 0.5) * 18 * p.depth
  const parallaxY = (mouseY - 0.5) * 12 * p.depth
  const x = p.x + parallaxX
  const y = p.y + parallaxY
  const { r, g, b } = p.palette
  const alpha = p.glow * twinkle

  const gradient = ctx.createRadialGradient(x, y, 0, x, y, p.size * 4.5)
  gradient.addColorStop(0, `rgba(${r},${g},${b},${alpha * 0.95})`)
  gradient.addColorStop(0.35, `rgba(${r},${g},${b},${alpha * 0.35})`)
  gradient.addColorStop(1, `rgba(${r},${g},${b},0)`)

  ctx.fillStyle = gradient
  ctx.beginPath()
  ctx.arc(x, y, p.size * 4.5, 0, Math.PI * 2)
  ctx.fill()

  ctx.fillStyle = `rgba(255,255,255,${alpha * 0.55})`
  ctx.beginPath()
  ctx.arc(x, y, p.size * 0.55, 0, Math.PI * 2)
  ctx.fill()
}

function tick(time) {
  const canvas = canvasRef.value
  if (!canvas) return
  const ctx = canvas.getContext('2d')
  if (!ctx) return

  ctx.clearRect(0, 0, width, height)

  if (!reducedMotion) {
    for (const p of particles) {
      p.x += p.vx + Math.sin(time * 0.0004 + p.phase) * 0.06 * p.depth
      p.y += p.vy

      if (p.y < -30) {
        Object.assign(p, spawnParticle(false))
        p.y = height + 10
      }
      if (p.x < -20) p.x = width + 20
      if (p.x > width + 20) p.x = -20

      drawParticle(ctx, p, time)
    }
  } else {
    for (const p of particles) {
      drawParticle(ctx, p, time * 0.2)
    }
  }

  rafId = requestAnimationFrame(tick)
}

function onMouseMove(e) {
  mouseX = e.clientX / Math.max(width, 1)
  mouseY = e.clientY / Math.max(height, 1)
}

function onVisibilityChange() {
  if (document.hidden) {
    cancelAnimationFrame(rafId)
  } else {
    rafId = requestAnimationFrame(tick)
  }
}

onMounted(() => {
  reducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches
  resize()
  window.addEventListener('resize', resize, { passive: true })
  window.addEventListener('mousemove', onMouseMove, { passive: true })
  document.addEventListener('visibilitychange', onVisibilityChange)
  rafId = requestAnimationFrame(tick)
})

onUnmounted(() => {
  cancelAnimationFrame(rafId)
  window.removeEventListener('resize', resize)
  window.removeEventListener('mousemove', onMouseMove)
  document.removeEventListener('visibilitychange', onVisibilityChange)
})
</script>

<style scoped>
.landing-particles {
  position: fixed;
  inset: 0;
  z-index: 2;
  pointer-events: none;
  opacity: 0.85;
  mix-blend-mode: screen;
}

@media (prefers-reduced-motion: reduce) {
  .landing-particles {
    opacity: 0.45;
  }
}

/* 浅色模式覆盖：深色默认不动，仅 html.light 下追加浅色取值。
   原 mix-blend-mode: screen 只在深色背景上提亮粒子，白底上无效；
   浅色改用 multiply 使浅色粒子在白底上可见（multiply 与 white 背景 = 粒子原色）。
   粒子白色高光核心在 multiply+白底下会隐没，仅保留彩色光晕，符合浅色氛围。
   略降整体透明度避免在浅色背景上过于浓重；JS 调色板不动。 */
html.light .landing-particles {
  mix-blend-mode: multiply;
  opacity: 0.7;
}
</style>
