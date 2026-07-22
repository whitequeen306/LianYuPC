<template>
  <nav
    class="dock-wheel"
    :class="{ 'is-open': isOpen, 'is-touch': !hoverCapable, 'is-hidden': !dockVisible }"
    aria-label="Main"
    @keydown.escape="close"
    @mouseenter="onDockEnter"
    @mouseleave="onDockLeave"
  >
    <div
      v-if="isOpen && !hoverCapable"
      class="dock-wheel__backdrop"
      aria-hidden="true"
      @click="close"
    />

    <div
      class="dock-wheel__wrap glass-strong"
      @mouseleave="onWrapLeave"
    >
      <div class="dock-wheel__fan" :class="{ 'is-open': isOpen }" aria-hidden="true">
        <div class="dock-wheel__stage">
          <router-link
            v-for="(item, index) in wheelItems"
            :key="item.path"
            :to="item.path"
            class="wheel-node glass"
            :class="{ active: isActive(item.path) }"
            :style="nodeStyle(index, wheelItems.length)"
            :tabindex="isOpen ? 0 : -1"
            :aria-label="item.label"
            @click="onWheelNavClick"
          >
            <span class="wheel-node__icon">
              <el-icon :size="20"><component :is="item.icon" /></el-icon>
            </span>
            <span class="wheel-node__label">{{ item.label }}</span>
          </router-link>
        </div>
      </div>

      <div class="dock-wheel__bar">
        <div
          ref="leftGroupRef"
          class="dock-primary-group"
          @mouseleave="onGroupLeave('left')"
        >
          <div ref="leftPillRef" class="dock-slider-pill" aria-hidden="true" />
          <router-link
            v-for="item in primaryLeft"
            :key="item.path"
            :to="item.path"
            class="dock-primary"
            :class="{ active: isActive(item.path) }"
            @mouseenter="onPrimaryHover('left', $event.currentTarget)"
          >
            <span class="dock-primary__icon">
              <el-icon :size="24"><component :is="item.icon" /></el-icon>
            </span>
            <span class="dock-primary__label">{{ item.label }}</span>
          </router-link>
        </div>

        <button
          type="button"
          class="dock-wheel__hub"
          :class="{ active: isOpen || isWheelRoute }"
          :aria-expanded="isOpen"
          :aria-label="t('nav.menuHub')"
          @mouseenter="onHubEnter"
          @click="onHubClick"
        >
          <span class="hub-ring" aria-hidden="true" />
          <span class="hub-icon" :class="{ 'is-open': isOpen }">
            <el-icon :size="26"><Compass /></el-icon>
          </span>
          <span class="hub-hint">{{ isOpen ? t('nav.menuClose') : t('nav.menuHub') }}</span>
        </button>

        <div
          ref="rightGroupRef"
          class="dock-primary-group"
          @mouseleave="onGroupLeave('right')"
        >
          <div ref="rightPillRef" class="dock-slider-pill" aria-hidden="true" />
          <div
            v-for="item in primaryRight"
            :key="item.path"
            class="dock-primary-wrap"
          >
            <transition name="dock-hint-fade">
              <OnboardingHintBubble
                v-if="item.path === '/app/character-square' && showSquareHint && isHomeRoute"
                placement="dock-top"
                :close-label="t('common.cancel')"
                @dismiss="dismissSquareHint"
              >
                {{ t('onboarding.squareHint') }}
              </OnboardingHintBubble>
            </transition>
            <router-link
              :to="item.path"
              class="dock-primary"
              :class="{ active: isActive(item.path) }"
              @mouseenter="onPrimaryHover('right', $event.currentTarget)"
              @click="onPrimaryNavClick(item.path)"
            >
              <span class="dock-primary__icon">
                <el-icon :size="24"><component :is="item.icon" /></el-icon>
              </span>
              <span class="dock-primary__label">{{ item.label }}</span>
            </router-link>
          </div>
        </div>
      </div>
    </div>
  </nav>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { gsap } from 'gsap'
import { Compass, HomeFilled, ChatLineRound, Grid, PictureRounded, ChatDotRound, Notebook, Collection, UserFilled, Setting, ChatLineSquare } from '@element-plus/icons-vue'
import OnboardingHintBubble from '@/components/OnboardingHintBubble.vue'
import { useOnboardingHint } from '@/composables/useOnboardingHint'

const WHEEL_RADIUS = 78
const ARC_START_DEG = -155
const ARC_END_DEG = -25

const route = useRoute()
const { t } = useI18n()
const { visible: showSquareHint, dismiss: dismissSquareHint } = useOnboardingHint('character-square')
const isOpen = ref(false)
const hoverCapable = ref(true)
const dockVisible = ref(true)

const leftGroupRef = ref(null)
const rightGroupRef = ref(null)
const leftPillRef = ref(null)
const rightPillRef = ref(null)
const hoverSide = ref(null)

let closeTimer = null
let dockHideTimer = null
let leftPillTween = null
let rightPillTween = null

const primaryLeft = computed(() => [
  { path: '/app', label: t('nav.home'), icon: HomeFilled },
  { path: '/app/characters', label: t('nav.characters'), icon: ChatLineRound }
])

const primaryRight = computed(() => [
  { path: '/app/character-square', label: t('nav.characterSquare'), icon: Grid },
  { path: '/app/moments', label: t('nav.moments'), icon: PictureRounded }
])

const wheelItems = computed(() => [
  { path: '/app/community', label: t('nav.community'), icon: ChatLineSquare },
  { path: '/app/group-chat', label: t('nav.groupChat'), icon: ChatDotRound },
  { path: '/app/diary', label: t('nav.diary'), icon: Notebook },
  { path: '/app/memory', label: t('nav.memory'), icon: Collection },
  { path: '/app/profile', label: t('nav.profile'), icon: UserFilled },
  { path: '/app/settings', label: t('nav.settings'), icon: Setting }
])

const wheelPaths = computed(() => wheelItems.value.map(i => i.path))

const isWheelRoute = computed(() =>
  wheelPaths.value.some(p => route.path.startsWith(p))
)

const isHomeRoute = computed(() => route.path === '/app')

function nodeStyle(index, total) {
  const tRatio = total <= 1 ? 0.5 : index / (total - 1)
  const angleDeg = ARC_START_DEG + tRatio * (ARC_END_DEG - ARC_START_DEG)
  const rad = (angleDeg * Math.PI) / 180
  const x = Math.cos(rad) * WHEEL_RADIUS
  const y = Math.sin(rad) * WHEEL_RADIUS
  return {
    '--node-x': `${x}px`,
    '--node-y': `${y}px`,
    '--node-delay': `${index * 0.04}s`
  }
}

function isActive(path) {
  if (path === '/app') return route.path === '/app'
  return route.path.startsWith(path)
}

function open() {
  clearTimeout(closeTimer)
  isOpen.value = true
}

function close() {
  clearTimeout(closeTimer)
  isOpen.value = false
}

function onWrapLeave() {
  if (!hoverCapable.value) return
  closeTimer = setTimeout(close, 300)
}

/** 鼠标移到屏幕底部边缘时显示 dock，离开后延迟隐藏 */
function onGlobalMouseMove(e) {
  const threshold = Math.min(window.innerHeight - 80, window.innerHeight - window.innerHeight * 0.1)
  if (e.clientY > threshold) {
    clearTimeout(dockHideTimer)
    dockVisible.value = true
  } else {
    if (!dockHideTimer) {
      dockHideTimer = setTimeout(() => {
        dockVisible.value = false
        close()
        dockHideTimer = null
      }, 800)
    }
  }
}

function onDockEnter() {
  clearTimeout(dockHideTimer)
  dockVisible.value = true
}

function onDockLeave() {
  dockHideTimer = setTimeout(() => {
    dockVisible.value = false
    dockHideTimer = null
  }, 600)
}

function onHubEnter() {
  if (hoverCapable.value) open()
}

function onHubClick() {
  if (!hoverCapable.value) {
    isOpen.value = !isOpen.value
  }
}

function onWheelNavClick() {
  close()
}

function onPrimaryNavClick(path) {
  if (path === '/app/character-square') {
    dismissSquareHint()
  }
}

function findActiveLink(groupEl) {
  if (!groupEl) return null
  return groupEl.querySelector('.dock-primary.active') || groupEl.querySelector('.dock-primary')
}

function movePill(side, targetEl, immediate = false) {
  const groupEl = side === 'left' ? leftGroupRef.value : rightGroupRef.value
  const pillEl = side === 'left' ? leftPillRef.value : rightPillRef.value
  if (!groupEl || !pillEl || !targetEl) return

  const groupRect = groupEl.getBoundingClientRect()
  const itemRect = targetEl.getBoundingClientRect()
  const x = itemRect.left - groupRect.left
  const y = itemRect.top - groupRect.top

  if (side === 'left') leftPillTween?.kill()
  else rightPillTween?.kill()

  const tween = gsap.to(pillEl, {
    x,
    y,
    width: itemRect.width,
    height: itemRect.height,
    opacity: 1,
    duration: immediate ? 0 : 0.42,
    ease: 'power3.out',
    overwrite: true,
  })

  if (side === 'left') leftPillTween = tween
  else rightPillTween = tween
}

function syncActivePills(immediate = false) {
  if (hoverSide.value) return
  nextTick(() => {
    movePill('left', findActiveLink(leftGroupRef.value), immediate)
    movePill('right', findActiveLink(rightGroupRef.value), immediate)
  })
}

function onPrimaryHover(side, el) {
  hoverSide.value = side
  movePill(side, el)
}

function onGroupLeave(side) {
  if (hoverSide.value === side) {
    hoverSide.value = null
    syncActivePills()
  }
}

watch(() => route.path, () => {
  close()
  syncActivePills()
})

onMounted(() => {
  hoverCapable.value = window.matchMedia('(hover: hover) and (pointer: fine)').matches
  window.addEventListener('mousemove', onGlobalMouseMove, { passive: true })
  syncActivePills(true)
  window.addEventListener('resize', syncActivePills, { passive: true })
})

onBeforeUnmount(() => {
  clearTimeout(closeTimer)
  clearTimeout(dockHideTimer)
  window.removeEventListener('mousemove', onGlobalMouseMove)
  window.removeEventListener('resize', syncActivePills)
  leftPillTween?.kill()
  rightPillTween?.kill()
})
</script>

<style lang="scss" scoped>
.dock-wheel {
  position: fixed;
  left: 50%;
  bottom: calc(#{$space-3} + env(safe-area-inset-bottom, 0px));
  transform: translateX(-50%);
  z-index: $z-header;
  pointer-events: none;
  transition: opacity 0.35s cubic-bezier(0.4, 0, 0.2, 1), transform 0.35s cubic-bezier(0.4, 0, 0.2, 1);
  opacity: 1;

  &.is-hidden {
    opacity: 0;
    transform: translateX(-50%) translateY(16px);
    pointer-events: none;
  }

  &.is-touch.is-open {
    inset: 0;
    left: 0;
    bottom: 0;
    transform: none;
    display: flex;
    align-items: flex-end;
    justify-content: center;
    padding-bottom: calc(#{$space-3} + env(safe-area-inset-bottom, 0px));
  }
}

.dock-wheel__backdrop {
  position: fixed;
  inset: 0;
  background: rgba(12, 8, 18, 0.42);
  backdrop-filter: blur(4px);
  pointer-events: auto;
}

.dock-wheel__wrap {
  position: relative;
  display: flex;
  flex-direction: column;
  align-items: stretch;
  pointer-events: auto;
  border-radius: $radius-xl;
  box-shadow: $shadow-lg, 0 0 0 1px rgba($color-pink-rgb, 0.1);
  overflow: visible;
  transition: border-radius 0.3s ease;

  .dock-wheel.is-open & {
    border-radius: $radius-xl $radius-xl $radius-pill $radius-pill;
  }
}

.dock-wheel__fan {
  position: relative;
  width: 100%;
  height: 0;
  overflow: hidden;
  flex-shrink: 0;
  transition: height 0.32s cubic-bezier(0.4, 0, 0.2, 1);

  &.is-open {
    height: 100px;
    overflow: visible;
  }
}

.dock-wheel__stage {
  position: absolute;
  left: 50%;
  bottom: 0;
  width: 0;
  height: 0;
  pointer-events: none;
}

.dock-wheel__fan.is-open .dock-wheel__stage {
  pointer-events: auto;
}

.wheel-node {
  position: absolute;
  left: 0;
  top: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 3px;
  width: 52px;
  margin-left: -26px;
  margin-top: -26px;
  padding: $space-1 2px;
  border-radius: $radius-md;
  text-decoration: none;
  color: $color-text-secondary;
  box-shadow: $shadow-md, 0 0 0 1px rgba($color-pink-rgb, 0.1);
  opacity: 0;
  transform: translate(var(--node-x), var(--node-y)) scale(0.4);
  transition:
    opacity 0.26s ease var(--node-delay),
    transform 0.4s cubic-bezier(0.34, 1.45, 0.64, 1) var(--node-delay),
    color $transition-fast,
    box-shadow $transition-fast,
    background $transition-fast;
  pointer-events: none;
  overflow: visible;

  &:hover {
    color: $color-pink-primary;
    box-shadow: $shadow-lg, 0 0 16px rgba($color-pink-rgb, 0.2);
    z-index: 2;
  }

  &.active {
    color: $color-pink-primary;
    background: rgba($color-pink-rgb, 0.14);
  }
}

.dock-wheel__fan.is-open .wheel-node {
  opacity: 1;
  transform: translate(var(--node-x), var(--node-y)) scale(1);
  pointer-events: auto;
}

.wheel-node__icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  border-radius: $radius-full;
  background: rgba($color-pink-rgb, 0.08);
}

.wheel-node__label {
  font-size: 9px;
  font-weight: $font-weight-semibold;
  letter-spacing: 0.04em;
  white-space: nowrap;
  max-width: 56px;
  overflow: hidden;
  text-overflow: ellipsis;
  text-align: center;
}

.dock-wheel__bar {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 2px;
  padding: $space-2 $space-3;
  min-width: min(380px, calc(100vw - #{$space-6}));
}

.dock-primary-group {
  position: relative;
  display: flex;
  align-items: center;
  gap: 2px;
  flex: 1;
  min-width: 0;
}

.dock-slider-pill {
  position: absolute;
  left: 0;
  top: 0;
  width: 0;
  height: 0;
  border-radius: $radius-pill;
  background: rgba($color-pink-rgb, 0.12);
  box-shadow: inset 0 0 0 1px rgba($color-pink-rgb, 0.14);
  opacity: 0;
  pointer-events: none;
  z-index: 0;
  will-change: transform, width, height;
}

.dock-primary-wrap {
  position: relative;
  flex: 1;
  min-width: 0;
  max-width: 72px;
  display: flex;
  justify-content: center;
  overflow: visible;
  z-index: 2;
}

.dock-primary {
  position: relative;
  z-index: 1;
  width: 100%;
  min-width: 0;
  max-width: 72px;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 3px;
  padding: $space-2 $space-1;
  border-radius: $radius-pill;
  text-decoration: none;
  color: $color-text-muted;
  transition: color $transition-fast, transform $transition-fast;
  background: transparent;

  &:hover {
    color: $color-text-secondary;
  }

  &.active {
    color: $color-pink-primary;

    .dock-primary__icon {
      transform: translateY(-1px);
    }
  }
}

.dock-primary__icon {
  display: flex;
  transition: transform $transition-fast;
}

.dock-primary__label {
  font-size: 10px;
  font-weight: $font-weight-medium;
  letter-spacing: 0.04em;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 100%;
}

.dock-wheel__hub {
  position: relative;
  z-index: 2;
  flex-shrink: 0;
  width: 56px;
  height: 56px;
  margin: 0 $space-1;
  border-radius: $radius-full;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 1px;
  color: $color-text-secondary;
  background: rgba($color-pink-rgb, 0.06);
  box-shadow: inset 0 0 0 1px rgba($color-pink-rgb, 0.12);
  transition:
    color $transition-fast,
    transform $transition-spring,
    box-shadow $transition-fast,
    background $transition-fast;
  cursor: pointer;

  &:hover,
  &.active {
    color: $color-pink-primary;
    background: rgba($color-pink-rgb, 0.14);
    transform: scale(1.04);
    box-shadow: 0 0 24px rgba($color-pink-rgb, 0.22);
  }
}

.hub-ring {
  position: absolute;
  inset: -5px;
  border-radius: $radius-full;
  border: 1px dashed rgba($color-pink-rgb, 0.25);
  opacity: 0;
  transform: scale(0.9);
  transition: opacity 0.3s cubic-bezier(0.4, 0, 0.2, 1), transform 0.4s cubic-bezier(0.4, 0, 0.2, 1);
  pointer-events: none;

}

.dock-wheel.is-open .dock-wheel__hub .hub-ring {
  opacity: 1;
  transform: scale(1.28);
  animation: hub-ring-spin 20s linear infinite;
}

@keyframes hub-ring-spin {
  to {
    transform: scale(1.28) rotate(360deg);
  }
}

.hub-icon {
  display: flex;
  transition: transform 0.35s cubic-bezier(0.34, 1.45, 0.64, 1);

  &.is-open {
    transform: rotate(90deg);
  }
}

.hub-hint {
  font-size: 8px;
  font-weight: $font-weight-medium;
  letter-spacing: 0.1em;
  opacity: 0.72;
  max-width: 48px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

@media (prefers-reduced-motion: reduce) {
  .wheel-node,
  .hub-icon,
  .hub-ring,
  .dock-wheel__wrap {
    transition: none;
    animation: none;
  }

  .dock-wheel__fan.is-open .wheel-node {
    transform: translate(var(--node-x), var(--node-y)) scale(1);
  }
}

.dock-wheel:not(.is-open) .wheel-node {
  visibility: hidden;
}

.dock-hint-fade-enter-active,
.dock-hint-fade-leave-active {
  transition: opacity 0.2s cubic-bezier(0.4, 0, 0.2, 1), transform 0.2s cubic-bezier(0.4, 0, 0.2, 1);
}

.dock-hint-fade-enter-from,
.dock-hint-fade-leave-to {
  opacity: 0;
  transform: translateX(-50%) translateY(4px);
}

@media (max-width: 420px) {
  .dock-wheel__bar {
    min-width: calc(100vw - #{$space-4});
    padding: $space-2;
    gap: 0;
  }

  .dock-primary {
    max-width: 64px;
  }

  .dock-primary__label,
  .hub-hint {
    font-size: 9px;
  }

  .dock-wheel__hub {
    width: 50px;
    height: 50px;
    margin: 0 2px;
  }
}
</style>
