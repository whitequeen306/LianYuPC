<template>
  <div
    ref="rootRef"
    class="app-atmosphere"
    :class="{ 'app-atmosphere--light': !isDark }"
    aria-hidden="true"
  >
    <div class="app-atmosphere__mesh" />
    <div ref="orbARef" class="app-atmosphere__orb app-atmosphere__orb--a" />
    <div ref="orbBRef" class="app-atmosphere__orb app-atmosphere__orb--b" />
    <div class="app-atmosphere__grain" />
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { gsap } from 'gsap'
import { useSettingsStore } from '@/stores/settings'

const settingsStore = useSettingsStore()
const isDark = computed(() => settingsStore.theme === 'dark')

const rootRef = ref(null)
const orbARef = ref(null)
const orbBRef = ref(null)

const pointer = { x: 0, y: 0 }
const current = { ax: 0, ay: 0, bx: 0, by: 0 }
let tickerFn = null
let reducedMotion = false

function onMouseMove(e) {
  const cx = window.innerWidth / 2
  const cy = window.innerHeight / 2
  pointer.x = (e.clientX - cx) / cx
  pointer.y = (e.clientY - cy) / cy
}

function startTicker() {
  if (tickerFn) return
  tickerFn = () => {
    const targetAx = pointer.x * 28
    const targetAy = pointer.y * 22
    const targetBx = pointer.x * -18
    const targetBy = pointer.y * -14

    current.ax += (targetAx - current.ax) * 0.03
    current.ay += (targetAy - current.ay) * 0.03
    current.bx += (targetBx - current.bx) * 0.015
    current.by += (targetBy - current.by) * 0.015

    if (orbARef.value) {
      gsap.set(orbARef.value, { x: current.ax, y: current.ay })
    }
    if (orbBRef.value) {
      gsap.set(orbBRef.value, { x: current.bx, y: current.by })
    }
  }
  gsap.ticker.add(tickerFn)
}

function stopTicker() {
  if (!tickerFn) return
  gsap.ticker.remove(tickerFn)
  tickerFn = null
}

onMounted(() => {
  reducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches
  if (reducedMotion) return
  window.addEventListener('mousemove', onMouseMove, { passive: true })
  startTicker()
})

onBeforeUnmount(() => {
  window.removeEventListener('mousemove', onMouseMove)
  stopTicker()
})
</script>

<style lang="scss" scoped>
.app-atmosphere {
  position: fixed;
  inset: 0;
  pointer-events: none;
  z-index: 0;
  overflow: hidden;
}

.app-atmosphere--light {
  opacity: 0.25;
}

.app-atmosphere__mesh {
  position: absolute;
  inset: 0;
  background:
    radial-gradient(ellipse 70% 55% at 12% 8%, rgba($color-pink-rgb, 0.11) 0%, transparent 58%),
    radial-gradient(ellipse 55% 45% at 88% 18%, rgba(var(--ly-bg-surface-rgb), 0.45) 0%, transparent 52%),
    radial-gradient(ellipse 80% 60% at 50% 100%, rgba($color-pink-rgb, 0.06) 0%, transparent 55%);
}

.app-atmosphere__orb {
  position: absolute;
  border-radius: 50%;
  filter: blur(60px);
  opacity: 0.55;
  animation: appOrbDrift 18s ease-in-out infinite;
  will-change: transform;

  &--a {
    width: 280px;
    height: 280px;
    top: 12%;
    right: -4%;
    background: rgba($color-pink-rgb, 0.14);
  }

  &--b {
    width: 220px;
    height: 220px;
    bottom: 18%;
    left: -6%;
    background: rgba(var(--ly-bg-surface-rgb), 0.35);
    animation-delay: -9s;
  }
}

.app-atmosphere__grain {
  position: absolute;
  inset: 0;
  opacity: 0.035;
  background-image: url("data:image/svg+xml,%3Csvg viewBox='0 0 256 256' xmlns='http://www.w3.org/2000/svg'%3E%3Cfilter id='n'%3E%3CfeTurbulence type='fractalNoise' baseFrequency='0.85' numOctaves='4' stitchTiles='stitch'/%3E%3C/filter%3E%3Crect width='100%25' height='100%25' filter='url(%23n)'/%3E%3C/svg%3E");
}

@keyframes appOrbDrift {
  0%, 100% { transform: translate(0, 0) scale(1); }
  50% { transform: translate(-12px, 16px) scale(1.06); }
}

@media (prefers-reduced-motion: reduce) {
  .app-atmosphere__orb {
    animation: none;
  }
}
</style>
