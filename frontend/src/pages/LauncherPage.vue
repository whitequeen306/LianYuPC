<template>
  <div ref="containerRef" class="pet-root" @contextmenu.prevent="onContextMenu">
    <transition name="pet-toast-fade">
      <div v-if="toastText" class="pet-toast" role="status">
        {{ toastText }}
      </div>
    </transition>
    <div ref="wrapRef" class="pet-wrap">
      <div
        ref="petRef"
        class="pet-body"
        :style="{ backgroundImage: `url(${petSpriteUrl})` }"
        title="点击快速聊天，按住拖动"
        @pointerdown.prevent="onPointerDown"
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
const petSpriteUrl = ref(getPetSpriteUrl(getPetById(DEFAULT_PET_ID)))
const pointerState = ref(null)
const toastText = ref('')
const dragging = ref(false)
let shakeTimer = null
let toastTimer = null
let unsubscribeLauncherMessage = null
let gsapCtx = null

const { playAnim } = usePetSpriteAnimator(petRef)

function onPointerDown(e) {
  if (e.button !== 0) return
  pointerState.value = {
    startX: e.screenX,
    startY: e.screenY,
    lastX: e.screenX,
    lastY: e.screenY,
    moved: false,
  }
  playAnim('waiting', { loop: true })
  window.addEventListener('pointermove', onPointerMove)
  window.addEventListener('pointerup', onPointerUp, { once: true })
}

function onPointerMove(e) {
  const state = pointerState.value
  if (!state) return
  const totalDx = e.screenX - state.startX
  const totalDy = e.screenY - state.startY
  if (!state.moved && (Math.abs(totalDx) > 5 || Math.abs(totalDy) > 5)) {
    state.moved = true
    dragging.value = true
    playAnim(totalDx >= 0 ? 'run-right' : 'run-left', { loop: true })
  }
  if (state.moved) {
    const dx = e.screenX - state.lastX
    const dy = e.screenY - state.lastY
    state.lastX = e.screenX
    state.lastY = e.screenY
    getElectronAPI()?.moveLauncherByDelta?.(dx, dy)
  }
}

function onPointerUp() {
  window.removeEventListener('pointermove', onPointerMove)
  const state = pointerState.value
  dragging.value = false
  if (state && !state.moved) {
    playAnim('wave')
    getElectronAPI()?.toggleCharacterPicker?.()
  } else {
    playAnim('idle')
  }
  pointerState.value = null
}

function onContextMenu() {
  playAnim('review')
  getElectronAPI()?.openMainWindow?.('#/app')
}

function showNewMessageHint(payload = {}) {
  const name = payload.characterName || t('launcher.defaultCharacterName')
  toastText.value = t('launcher.newMessageHint', { name })
  playAnim('jump')
  clearTimeout(shakeTimer)
  clearTimeout(toastTimer)
  shakeTimer = setTimeout(() => {
    if (!dragging.value) playAnim('idle')
  }, 900)
  toastTimer = setTimeout(() => {
    toastText.value = ''
  }, 4200)
}

onMounted(async () => {
  const settings = await getElectronAPI()?.getDesktopSettings?.()
  if (settings?.launcherPetId) {
    petSpriteUrl.value = getPetSpriteUrl(getPetById(settings.launcherPetId))
  }
  if (wrapRef.value) {
    gsapCtx = gsap.context(() => {
      gsap.to(wrapRef.value, {
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
})

onUnmounted(() => {
  clearTimeout(shakeTimer)
  clearTimeout(toastTimer)
  unsubscribeLauncherMessage?.()
  gsapCtx?.revert()
})
</script>

<style lang="scss">
html:has(.pet-root),
body:has(.pet-root),
#app:has(.pet-root) {
  background: transparent !important;
  overflow: hidden;
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
}

.pet-wrap {
  will-change: transform;
}

.pet-body {
  width: 192px;
  height: 208px;
  background-size: 1536px 1872px;
  background-repeat: no-repeat;
  background-position: 0 0;
  cursor: pointer;
  touch-action: none;
  filter: drop-shadow(0 2px 8px rgba(0, 0, 0, 0.25));
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
