<template>
  <div ref="containerRef" class="pet-root" @contextmenu.prevent="onContextMenu">
    <transition name="pet-toast-fade">
      <div v-if="toastText" class="pet-toast" role="status">
        {{ toastText }}
      </div>
    </transition>
    <div ref="wrapRef" class="pet-wrap">
      <canvas
        ref="petRef"
        class="pet-canvas"
        width="192"
        height="208"
        aria-hidden="true"
      />
      <div
        ref="hitboxRef"
        class="pet-hitbox"
        title="单击选角色，双击跳跃，按住拖动"
        @pointerdown.prevent="onPointerDown"
        @dblclick.prevent="onDoubleClick"
      />
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { gsap } from 'gsap'
import { getElectronAPI } from '@/utils/electron'
import { DEFAULT_PET_ID, getPetById, getPetSpriteUrl } from '@/constants/petCatalog'
import { usePetSpriteAnimator } from '@/composables/usePetSpriteAnimator'

const { t } = useI18n()
const containerRef = ref(null)
const wrapRef = ref(null)
const petRef = ref(null)
const hitboxRef = ref(null)
const pointerState = ref(null)
const toastText = ref('')
const dragging = ref(false)
let clickTimer = null
let toastTimer = null
let unsubscribeLauncherMessage = null
let unsubscribePetChanged = null
let gsapCtx = null
let idleFloatTween = null

const { playAnim, playAnimOnce, returnToIdle, setSpriteImage } = usePetSpriteAnimator(petRef)

function setIdleFloatPaused(paused) {
  if (!idleFloatTween) return
  if (paused) idleFloatTween.pause()
  else idleFloatTween.play()
}

function applyPetId(petId) {
  const pet = getPetById(petId)
  const url = getPetSpriteUrl(pet)
  const img = new Image()
  img.onload = () => {
    setSpriteImage(img)
    playAnim('idle')
  }
  img.onerror = () => {
    playAnim('idle')
  }
  img.src = url
}

function resetWrapTransform() {
  if (wrapRef.value) gsap.set(wrapRef.value, { y: 0 })
}

function syncMousePassthrough(clientX, clientY) {
  if (dragging.value || pointerState.value) {
    getElectronAPI()?.setLauncherMousePassthrough?.(false)
    return
  }
  const target = document.elementFromPoint(clientX, clientY)
  const hitInteractive = target?.closest?.('.pet-hitbox, .pet-toast')
  getElectronAPI()?.setLauncherMousePassthrough?.(!hitInteractive)
}

function onDocumentMouseMove(e) {
  syncMousePassthrough(e.clientX, e.clientY)
}

function onDocumentMouseLeave() {
  if (dragging.value || pointerState.value) return
  getElectronAPI()?.setLauncherMousePassthrough?.(true)
}

function releasePointerCapture(state) {
  if (!state || state.pointerId == null || !hitboxRef.value) return
  try {
    if (hitboxRef.value.hasPointerCapture(state.pointerId)) {
      hitboxRef.value.releasePointerCapture(state.pointerId)
    }
  } catch {
    // ignore
  }
}

function onDoubleClick() {
  if (clickTimer) {
    clearTimeout(clickTimer)
    clickTimer = null
  }
  if (dragging.value || pointerState.value?.moved) return
  playAnimOnce('jump')
}

function onPointerDown(e) {
  if (e.button !== 0) return
  getElectronAPI()?.setLauncherMousePassthrough?.(false)
  hitboxRef.value?.setPointerCapture?.(e.pointerId)
  pointerState.value = {
    startX: e.screenX,
    startY: e.screenY,
    lastScreenX: e.screenX,
    lastScreenY: e.screenY,
    pointerId: e.pointerId,
    moved: false,
    runAnim: null,
  }
  setIdleFloatPaused(true)
  resetWrapTransform()
  playAnim('waiting', { loop: true })
  window.addEventListener('pointermove', onPointerMove)
  window.addEventListener('pointerup', onPointerUp)
  window.addEventListener('pointercancel', onPointerUp)
}

function onPointerMove(e) {
  const state = pointerState.value
  if (!state || e.pointerId !== state.pointerId) return
  const totalDx = e.screenX - state.startX
  const totalDy = e.screenY - state.startY
  if (!state.moved && (Math.abs(totalDx) > 5 || Math.abs(totalDy) > 5)) {
    state.moved = true
    dragging.value = true
    getElectronAPI()?.beginLauncherDrag?.()
    const runAnim = totalDx >= 0 ? 'run-right' : 'run-left'
    state.runAnim = runAnim
    playAnim(runAnim, { loop: true })
  }
  if (state.moved) {
    const dx = e.screenX - state.lastScreenX
    const dy = e.screenY - state.lastScreenY
    state.lastScreenX = e.screenX
    state.lastScreenY = e.screenY
    if (dx !== 0) {
      const runAnim = dx >= 0 ? 'run-right' : 'run-left'
      if (state.runAnim !== runAnim) {
        state.runAnim = runAnim
        playAnim(runAnim, { loop: true })
      }
    }
    if (dx !== 0 || dy !== 0) {
      getElectronAPI()?.moveLauncherDrag?.(dx, dy)
    }
  }
}

function onPointerUp(e) {
  const state = pointerState.value
  if (state && e.pointerId !== state.pointerId) return
  window.removeEventListener('pointermove', onPointerMove)
  window.removeEventListener('pointerup', onPointerUp)
  window.removeEventListener('pointercancel', onPointerUp)
  releasePointerCapture(state)
  dragging.value = false
  if (state && !state.moved) {
    clickTimer = setTimeout(() => {
      clickTimer = null
      playAnimOnce('wave')
      getElectronAPI()?.toggleCharacterPicker?.()
    }, 240)
  } else {
    playAnimOnce('running', () => returnToIdle())
    getElectronAPI()?.endLauncherDrag?.()
  }
  setIdleFloatPaused(false)
  pointerState.value = null
  if (e) syncMousePassthrough(e.clientX, e.clientY)
}

function onContextMenu() {
  playAnimOnce('review')
  getElectronAPI()?.openMainWindow?.('#/app')
}

function showNewMessageHint(payload = {}) {
  const name = payload.characterName || t('launcher.defaultCharacterName')
  toastText.value = t('launcher.newMessageHint', { name })
  playAnimOnce('jump')
  clearTimeout(toastTimer)
  toastTimer = setTimeout(() => {
    toastText.value = ''
  }, 4200)
}

onMounted(async () => {
  document.documentElement.style.background = 'transparent'
  document.body.style.background = 'transparent'
  const appEl = document.getElementById('app')
  if (appEl) appEl.style.background = 'transparent'
  document.addEventListener('mousemove', onDocumentMouseMove)
  document.addEventListener('mouseleave', onDocumentMouseLeave)
  getElectronAPI()?.setLauncherMousePassthrough?.(true)
  const settings = await getElectronAPI()?.getDesktopSettings?.()
  applyPetId(settings?.launcherPetId || DEFAULT_PET_ID)
  if (wrapRef.value) {
    gsapCtx = gsap.context(() => {
      idleFloatTween = gsap.to(wrapRef.value, {
        y: -4,
        duration: 2.4,
        ease: 'sine.inOut',
        yoyo: true,
        repeat: -1,
        force3D: false,
      })
    }, containerRef.value)
  }
  unsubscribeLauncherMessage = getElectronAPI()?.onLauncherNewMessage?.(showNewMessageHint)
  unsubscribePetChanged = getElectronAPI()?.onLauncherPetChanged?.(applyPetId)
})

onUnmounted(() => {
  document.removeEventListener('mousemove', onDocumentMouseMove)
  document.removeEventListener('mouseleave', onDocumentMouseLeave)
  clearTimeout(clickTimer)
  clearTimeout(toastTimer)
  unsubscribeLauncherMessage?.()
  unsubscribePetChanged?.()
  gsapCtx?.revert()
})
</script>

<style lang="scss">
html:has(.pet-root),
body:has(.pet-root),
#app:has(.pet-root) {
  background: transparent !important;
  overflow: hidden;
  min-height: 0 !important;
}

#app:has(.pet-root)::before {
  display: none !important;
  content: none !important;
  animation: none !important;
}
</style>

<style lang="scss" scoped>
.pet-root {
  width: 100%;
  height: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: flex-end;
  box-sizing: border-box;
  user-select: none;
  pointer-events: none;
}

.pet-toast {
  max-width: 100%;
  margin-bottom: 4px;
  padding: 5px 8px;
  border-radius: 10px;
  background: rgba(14, 14, 22, 0.92);
  border: 1px solid rgba(236, 72, 153, 0.28);
  box-shadow: 0 6px 20px rgba(0, 0, 0, 0.35);
  color: #f5f5f7;
  font-size: 11px;
  line-height: 1.4;
  text-align: center;
  word-break: break-all;
  pointer-events: auto;
}

.pet-wrap {
  position: relative;
  width: 192px;
  height: 208px;
  pointer-events: none;
}

.pet-canvas {
  display: block;
  width: 192px;
  height: 208px;
  pointer-events: none;
  image-rendering: pixelated;
  image-rendering: crisp-edges;
}

.pet-hitbox {
  position: absolute;
  left: 50%;
  bottom: 6px;
  width: 120px;
  height: 156px;
  transform: translateX(-50%);
  cursor: pointer;
  touch-action: none;
  pointer-events: auto;
}

.pet-toast-fade-enter-active,
.pet-toast-fade-leave-active {
  transition: opacity 0.2s ease, transform 0.2s ease;
}

.pet-toast-fade-enter-from,
.pet-toast-fade-leave-to {
  opacity: 0;
  transform: translateY(6px);
}
</style>
