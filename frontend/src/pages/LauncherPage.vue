<template>
  <div
    ref="containerRef"
    class="pet-root"
    :class="{ 'pet-root--picker-open': pickerOpen }"
  >
    <LauncherPickPanel v-if="pickerOpen" class="pet-picker" />
    <transition name="pet-toast-fade">
      <div v-if="toastText" class="pet-toast" role="status">
        {{ toastText }}
      </div>
    </transition>
    <transition name="pet-greeting-pop">
      <div v-if="greetingText" class="pet-greeting" role="status" @click="dismissGreeting">
        {{ greetingText }}
      </div>
    </transition>
    <transition name="pet-observe-fade">
      <div v-if="isObserving" class="pet-observe-badge" role="status" aria-label="正在观察屏幕">
        ● 观察中
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
        title="单击选角色，按住拖动"
        @contextmenu.prevent="onContextMenu"
        @pointerdown.prevent="onPointerDown"
      />
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import { getActivePinia } from 'pinia'
import { useI18n } from 'vue-i18n'
import { gsap } from 'gsap'
import { getElectronAPI } from '@/utils/electron'
import LauncherPickPanel from '@/components/LauncherPickPanel.vue'
import { useUserStore } from '@/stores/user'
import { useCharactersStore } from '@/stores/characters'
import { useConversationsStore } from '@/stores/conversations'
import { DEFAULT_PET_ID, getPetById, getPetIdleUrl, getPetSpriteUrl, getPetPersona } from '@/constants/petCatalog'
import { usePetSpriteAnimator } from '@/composables/usePetSpriteAnimator'
import { refreshLauncherSession } from '@/auth/launcherBootstrap'

const { t } = useI18n()
const containerRef = ref(null)
const wrapRef = ref(null)
const petRef = ref(null)
const hitboxRef = ref(null)
const pointerState = ref(null)
const toastText = ref('')
const greetingText = ref('')
const isObserving = ref(false)
const pickerOpen = ref(false)
const dragging = ref(false)
const currentPetId = ref(DEFAULT_PET_ID)
let clickTimer = null
let toastTimer = null
let greetingTimer = null
let mouseInsideSprite = false
let unsubscribeLauncherMessage = null
let unsubscribePetChanged = null
let unsubscribeInteractionReset = null
let unsubscribeGreeting = null
let unsubscribeRestartObserver = null
let unsubscribeObserveCapturing = null
let unsubscribeLauncherShown = null
let unsubscribeLauncherHidden = null
let unsubscribePickerToggle = null
let launcherActive = false
let gsapCtx = null
let idleFloatTween = null
let dragRafId = null
let pendingDx = 0
let pendingDy = 0
let greetingAudio = null
/** 自动播放被拦截时，等用户点击桌宠再播 */
let pendingGreetingAudioPayload = null

const { playAnim, playAnimOnce, returnToIdle, setSpriteImage, setIdleFrame } = usePetSpriteAnimator(petRef)

async function prefetchPickerData() {
  const userStore = useUserStore()
  if (!userStore.token) {
    await refreshLauncherSession(getActivePinia())
  }
  if (!userStore.isLoggedIn && !userStore.token) return
  const charactersStore = useCharactersStore()
  const conversationsStore = useConversationsStore()
  void charactersStore.fetchList()
  void conversationsStore.fetchList({ silent: true }).catch(() => [])
}

function setIdleFloatPaused(paused) {
  if (!idleFloatTween) return
  if (paused) idleFloatTween.pause()
  else idleFloatTween.play()
}

function applyWrapTransform(x, y) {
  if (!wrapRef.value) return
  if (!x && !y) {
    wrapRef.value.style.transform = ''
    return
  }
  wrapRef.value.style.transform = `translate(${x}px, ${y}px)`
}

function flushDragDelta() {
  dragRafId = null
  if (pendingDx === 0 && pendingDy === 0) return
  const dx = pendingDx
  const dy = pendingDy
  pendingDx = 0
  pendingDy = 0
  try {
    getElectronAPI()?.moveLauncherDrag?.(dx, dy)
    const state = pointerState.value
    if (state) {
      state.offsetX = (state.offsetX || 0) - dx
      state.offsetY = (state.offsetY || 0) - dy
      applyWrapTransform(state.offsetX, state.offsetY)
    }
  } catch {
    // ignore IPC failures during rapid drag
  }
}

function scheduleDragFlush() {
  if (dragRafId != null) return
  dragRafId = requestAnimationFrame(flushDragDelta)
}

function applyPetId(petId) {
  currentPetId.value = petId || DEFAULT_PET_ID
  const pet = getPetById(currentPetId.value)
  setIdleFrame(getPetIdleUrl(currentPetId.value))
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
  if (launcherActive) startObserver()
}

function stopObserver() {
  getElectronAPI()?.stopDesktopObserver?.()
}

function startObserver() {
  const api = getElectronAPI()
  if (!api?.startDesktopObserver) return
  const pet = getPetById(currentPetId.value)
  const persona = getPetPersona(pet)
  if (!persona) return
  void api.startDesktopObserver({
    persona,
    petId: currentPetId.value,
  }).then((res) => {
    if (res && !res.ok) {
      console.warn('[launcher] screen observe not started:', res.reason)
    }
  })
}

function resetWrapTransform() {
  const state = pointerState.value
  if (state) {
    state.offsetX = 0
    state.offsetY = 0
  }
  applyWrapTransform(0, 0)
}

function resetInteractionState() {
  if (clickTimer) {
    clearTimeout(clickTimer)
    clickTimer = null
  }
  if (dragRafId != null) {
    cancelAnimationFrame(dragRafId)
    dragRafId = null
    if (pendingDx !== 0 || pendingDy !== 0) {
      try { getElectronAPI()?.moveLauncherDrag?.(pendingDx, pendingDy) } catch { /* ignore */ }
    }
    pendingDx = 0
    pendingDy = 0
  }
  window.removeEventListener('pointermove', onPointerMove)
  window.removeEventListener('pointerup', onPointerUp)
  window.removeEventListener('pointercancel', onPointerUp)
  if (pointerState.value) {
    releasePointerCapture(pointerState.value)
  }
  dragging.value = false
  pointerState.value = null
  setIdleFloatPaused(false)
}

function releasePointerCapture(state) {
  if (!state || state.pointerId == null || !hitboxRef.value) return
  try {
    if (hitboxRef.value.hasPointerCapture(state.pointerId)) {
      hitboxRef.value.releasePointerCapture(state.pointerId)
    }
  } catch { /* ignore */ }
}

// 鼠标穿透/捕获切换：主进程默认穿透+转发，渲染层在鼠标进入精灵区域时切回捕获，离开时放行。
// 这样透明边框区域的点击会穿透到桌面文件（修复“白边挡点击”）。
function onSpriteHoverTest(e) {
  if (pickerOpen.value || pointerState.value) return
  const wrap = wrapRef.value
  if (!wrap) return
  const r = wrap.getBoundingClientRect()
  const inside = e.clientX >= r.left && e.clientX <= r.right
    && e.clientY >= r.top && e.clientY <= r.bottom
  if (inside !== mouseInsideSprite) {
    mouseInsideSprite = inside
    getElectronAPI()?.setLauncherMousePassthrough?.(!inside)
  }
}

function onPointerDown(e) {
  if (e.button !== 0) return
  flushPendingGreetingAudio()
  getElectronAPI()?.setLauncherMousePassthrough?.(false)
  hitboxRef.value?.setPointerCapture?.(e.pointerId)
  pointerState.value = {
    startX: e.screenX, startY: e.screenY,
    lastScreenX: e.screenX, lastScreenY: e.screenY,
    pointerId: e.pointerId, moved: false, runAnim: null,
    offsetX: 0, offsetY: 0,
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
    try { getElectronAPI()?.beginLauncherDrag?.() } catch { /* ignore */ }
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
      if (state.runAnim !== runAnim) { state.runAnim = runAnim; playAnim(runAnim, { loop: true }) }
    }
    if (dx !== 0 || dy !== 0) {
      pendingDx += dx
      pendingDy += dy
      state.offsetX = (state.offsetX || 0) + dx
      state.offsetY = (state.offsetY || 0) + dy
      applyWrapTransform(state.offsetX, state.offsetY)
      scheduleDragFlush()
    }
  }
}

function onPointerUp(e) {
  const state = pointerState.value
  if (state && e?.pointerId != null && e.pointerId !== state.pointerId) return
  const wasMoved = !!state?.moved
  window.removeEventListener('pointermove', onPointerMove)
  window.removeEventListener('pointerup', onPointerUp)
  window.removeEventListener('pointercancel', onPointerUp)
  releasePointerCapture(state)
  dragging.value = false
  if (dragRafId != null) { cancelAnimationFrame(dragRafId); dragRafId = null }
  if (wasMoved && (pendingDx !== 0 || pendingDy !== 0)) {
    flushDragDelta()
  } else {
    pendingDx = 0
    pendingDy = 0
  }
  resetWrapTransform()
  if (state && !state.moved) {
    clickTimer = setTimeout(() => {
      clickTimer = null
      playAnimOnce('wave')
      getElectronAPI()?.toggleCharacterPicker?.()
    }, 240)
  } else if (wasMoved) {
    playAnimOnce('running', () => returnToIdle())
    try { getElectronAPI()?.endLauncherDrag?.() } catch { /* ignore */ }
  }
  setIdleFloatPaused(false)
  pointerState.value = null
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
  toastTimer = setTimeout(() => { toastText.value = '' }, 4200)
}

function stopGreetingAudio() {
  if (!greetingAudio) return
  try {
    greetingAudio.pause()
    greetingAudio.currentTime = 0
  } catch {
    // ignore
  }
  greetingAudio = null
}

function playGreetingAudio(payload = {}) {
  const mime = payload.audioMimeType || 'audio/wav'
  const src = payload.audioUrl
    || (payload.audioBase64 ? `data:${mime};base64,${payload.audioBase64}` : '')
  if (!src) {
    console.warn('[launcher] greeting has no audioUrl/audioBase64')
    return false
  }
  stopGreetingAudio()
  try {
    greetingAudio = new Audio(src)
    greetingAudio.volume = 0.92
    greetingAudio.onerror = () => {
      const code = greetingAudio?.error?.code
      console.warn('[launcher] greeting audio element error, code=', code, 'src=', src.slice(0, 80))
      pendingGreetingAudioPayload = payload
    }
    const playPromise = greetingAudio.play()
    if (playPromise && typeof playPromise.then === 'function') {
      playPromise.then(() => {
        pendingGreetingAudioPayload = null
      }).catch((err) => {
        console.warn('[launcher] greeting audio autoplay blocked:', err?.message || err)
        pendingGreetingAudioPayload = payload
      })
    } else {
      pendingGreetingAudioPayload = null
    }
    return true
  } catch (err) {
    console.warn('[launcher] greeting audio failed:', err?.message || err)
    pendingGreetingAudioPayload = payload
    greetingAudio = null
    return false
  }
}

function flushPendingGreetingAudio() {
  if (!pendingGreetingAudioPayload) return
  const payload = pendingGreetingAudioPayload
  pendingGreetingAudioPayload = null
  playGreetingAudio(payload)
}

function showGreeting(payload = {}) {
  const text = payload.text || ''
  if (!text) return
  greetingText.value = text
  playGreetingAudio(payload)
  // greeting 动画: wave 挥手 → 短暂停顿 → jump 弹跳
  playAnimOnce('wave', () => {
    setTimeout(() => { playAnimOnce('jump') }, 300)
  })
  clearTimeout(greetingTimer)
  greetingTimer = setTimeout(() => { greetingText.value = '' }, 10000)
}

function dismissGreeting() {
  greetingText.value = ''
  clearTimeout(greetingTimer)
  stopGreetingAudio()
}

onMounted(async () => {
  document.documentElement.style.background = 'transparent'
  document.body.style.background = 'transparent'
  const appEl = document.getElementById('app')
  if (appEl) appEl.style.background = 'transparent'
  getElectronAPI()?.isLauncherVisible?.().then((visible) => {
    if (visible) {
      launcherActive = true
      startObserver()
    }
  })
  const settings = await getElectronAPI()?.getDesktopSettings?.()
  applyPetId(settings?.launcherPetId || DEFAULT_PET_ID)
  getElectronAPI()?.setLauncherMousePassthrough?.(false)
  if (wrapRef.value) {
    gsapCtx = gsap.context(() => {
      idleFloatTween = gsap.to(wrapRef.value, {
        y: -3, duration: 3.2, ease: 'sine.inOut', yoyo: true, repeat: -1, force3D: true,
      })
    }, containerRef.value)
  }
  unsubscribeLauncherMessage = getElectronAPI()?.onLauncherNewMessage?.(showNewMessageHint)
  unsubscribePetChanged = getElectronAPI()?.onLauncherPetChanged?.(applyPetId)
  unsubscribeInteractionReset = getElectronAPI()?.onLauncherInteractionReset?.(() => {
    resetInteractionState()
    returnToIdle()
  })
  unsubscribeGreeting = getElectronAPI()?.onLauncherGreeting?.(showGreeting)
  unsubscribeRestartObserver = getElectronAPI()?.onRestartObserver?.(startObserver)
  unsubscribeObserveCapturing = getElectronAPI()?.onObserveCapturing?.((capturing) => {
    isObserving.value = !!capturing
  })
  unsubscribeLauncherShown = getElectronAPI()?.onLauncherShown?.(async () => {
    launcherActive = true
    const settings = await getElectronAPI()?.getDesktopSettings?.()
    const nextPetId = settings?.launcherPetId
    if (nextPetId && nextPetId !== currentPetId.value) {
      applyPetId(nextPetId)
    }
    prefetchPickerData()
    startObserver()
  })
  unsubscribeLauncherHidden = getElectronAPI()?.onLauncherHidden?.(() => {
    launcherActive = false
    stopObserver()
    pickerOpen.value = false
  })
  unsubscribePickerToggle = getElectronAPI()?.onPickerToggle?.((payload) => {
    const open = payload?.open === true
    pickerOpen.value = open
    if (open) prefetchPickerData()
    if (!open) {
      resetInteractionState()
      returnToIdle()
    }
  })
  prefetchPickerData()
  window.addEventListener('mousemove', onSpriteHoverTest)
})

onUnmounted(() => {
  clearTimeout(clickTimer)
  clearTimeout(toastTimer)
  clearTimeout(greetingTimer)
  stopGreetingAudio()
  if (dragRafId != null) { cancelAnimationFrame(dragRafId); dragRafId = null }
  window.removeEventListener('mousemove', onSpriteHoverTest)
  getElectronAPI()?.stopDesktopObserver?.()
  unsubscribeObserveCapturing?.()
  unsubscribeLauncherMessage?.()
  unsubscribePetChanged?.()
  unsubscribeInteractionReset?.()
  unsubscribeGreeting?.()
  unsubscribeRestartObserver?.()
  unsubscribeLauncherShown?.()
  unsubscribeLauncherHidden?.()
  unsubscribePickerToggle?.()
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
  display: none !important; content: none !important; animation: none !important;
}
</style>

<style lang="scss" scoped>
.pet-root {
  width: 100%; height: 100%;
  display: flex; flex-direction: column;
  align-items: center; justify-content: flex-end;
  box-sizing: border-box;
  user-select: none; pointer-events: none;
}

.pet-root--picker-open {
  justify-content: flex-start;
  pointer-events: auto;
}

.pet-picker {
  flex: 0 0 320px;
  width: 100%;
  height: 320px;
  pointer-events: auto;
}

.pet-observe-badge {
  margin-bottom: 6px;
  padding: 3px 10px;
  border-radius: 999px;
  background: rgba(220, 38, 38, 0.92);
  border: 1px solid rgba(255, 80, 80, 0.6);
  box-shadow: 0 0 16px rgba(220, 38, 38, 0.5);
  color: #fff; font-size: 11px; font-weight: 600;
  letter-spacing: 0.5px;
  pointer-events: none;
  animation: pet-observe-pulse 1.4s ease-in-out infinite;
}
.pet-observe-fade-enter-active, .pet-observe-fade-leave-active {
  transition: opacity 0.2s ease;
}
.pet-observe-fade-enter-from, .pet-observe-fade-leave-to {
  opacity: 0;
}
@keyframes pet-observe-pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.55; }
}
.pet-toast {
  max-width: 100%; margin-bottom: 4px;
  padding: 5px 8px; border-radius: 10px;
  background: rgba(14, 14, 22, 0.92);
  border: 1px solid rgba(236, 72, 153, 0.28);
  box-shadow: 0 6px 20px rgba(0, 0, 0, 0.35);
  color: #f5f5f7; font-size: 11px;
  line-height: 1.4; text-align: center;
  word-break: break-all; pointer-events: auto;
}

.pet-greeting {
  max-width: 92%; margin-bottom: 6px;
  padding: 8px 14px; border-radius: 14px;
  background: linear-gradient(135deg, rgba(236, 72, 153, 0.90), rgba(168, 85, 247, 0.85));
  border: 1px solid rgba(236, 72, 153, 0.55);
  box-shadow:
    0 0 24px rgba(236, 72, 153, 0.28),
    0 8px 28px rgba(0, 0, 0, 0.42);
  color: #fff; font-size: 14px; font-weight: 500;
  line-height: 1.45; text-align: center;
  word-break: break-word; pointer-events: auto;
  cursor: pointer;
}

.pet-wrap {
  position: relative; width: 192px; height: 208px;
  pointer-events: none;
  will-change: transform;
}

.pet-canvas {
  display: block; width: 192px; height: 208px;
  pointer-events: none;
  image-rendering: pixelated; image-rendering: crisp-edges;
}

.pet-hitbox {
  position: absolute; left: 50%; bottom: 6px;
  width: 100px; height: 132px;
  transform: translateX(-50%);
  cursor: pointer; touch-action: none; pointer-events: auto;
}

.pet-toast-fade-enter-active,
.pet-toast-fade-leave-active {
  transition: opacity 0.2s ease, transform 0.2s ease;
}
.pet-toast-fade-enter-from,
.pet-toast-fade-leave-to {
  opacity: 0; transform: translateY(6px);
}

.pet-greeting-pop-enter-active {
  transition: opacity 0.35s ease, transform 0.35s cubic-bezier(0.34, 1.56, 0.64, 1);
}
.pet-greeting-pop-leave-active {
  transition: opacity 0.3s ease, transform 0.3s ease;
}
.pet-greeting-pop-enter-from {
  opacity: 0; transform: translateY(12px) scale(0.85);
}
.pet-greeting-pop-leave-to {
  opacity: 0; transform: translateY(-4px) scale(0.95);
}
</style>
