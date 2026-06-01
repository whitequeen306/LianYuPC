import { nextTick, onBeforeUnmount, onMounted } from 'vue'
import gsap from 'gsap'

const CARD_COUNT = 6

const SHOWCASE_STEP = 1.05
const SHOWCASE_HOLD = 0.68
const CYCLE_HOLD = 0.62
const SPIN_DURATION = 48
const BOB_DURATION = 4

function slotAngle(index) {
  return (360 / CARD_COUNT) * index - 90
}

function prefersReducedMotion() {
  return window.matchMedia('(prefers-reduced-motion: reduce)').matches
}

function readOrbitRadiusPx(root) {
  const stage = root.querySelector('.hero-orbit__stage')
  if (!stage) return 160
  const raw = getComputedStyle(stage).getPropertyValue('--orbit-r').trim()
  const n = parseFloat(raw)
  return Number.isFinite(n) ? n : 160
}

function orbitOffset(index, radiusPx) {
  const rad = (slotAngle(index) * Math.PI) / 180
  return {
    x: Math.cos(rad) * radiusPx,
    y: Math.sin(rad) * radiusPx,
  }
}

function queueOffset(index) {
  const spacing = 44
  const mid = (CARD_COUNT - 1) / 2
  return { x: -28, y: (index - mid) * spacing }
}

function entryOffset(index) {
  return { x: 300, y: (index - 2.5) * 22 }
}

function spotlightOffset() {
  return { x: 12, y: -6 }
}

function getCards(slots) {
  return slots.map((s) => s.querySelector('.hero-orbit__card')).filter(Boolean)
}

function prepareSlotForCartesian(slot, face) {
  gsap.set(slot, {
    transform: 'none',
    rotation: 0,
    transformOrigin: '50% 50%',
  })
  if (face) {
    gsap.set(face, { rotation: 0, transform: 'none' })
  }
}

function dimOthersCards(tl, slots, activeIndex, position) {
  slots.forEach((slot, i) => {
    if (i === activeIndex) return
    tl.to(slot, { opacity: 0.38, duration: 0.22, ease: 'power2.out' }, position)
  })
}

function resetAllSlots(tl, slots, position) {
  tl.to(slots, { opacity: 1, duration: 0.22, ease: 'power2.out' }, position)
}

function spotlightCard(tl, card, position) {
  if (!card) return
  tl.to(
    card,
    {
      scale: 1.28,
      opacity: 1,
      boxShadow: '0 22px 52px rgba(244, 166, 181, 0.38)',
      duration: 0.4,
      ease: 'back.out(1.7)',
    },
    position,
  )
}

function resetCard(tl, card, position) {
  if (!card) return
  tl.to(
    card,
    {
      scale: 1,
      opacity: 1,
      boxShadow: '0 12px 28px rgba(0, 0, 0, 0.42)',
      duration: 0.28,
    },
    position,
  )
}

function showBubble(tl, bubble, position) {
  if (!bubble) return
  gsap.set(bubble, { xPercent: -50, left: '50%' })
  tl.fromTo(
    bubble,
    { autoAlpha: 0, y: 12, scale: 0.9 },
    { autoAlpha: 1, y: 0, scale: 1, duration: 0.34, ease: 'power2.out' },
    position,
  )
}

function hideBubble(tl, bubble, position) {
  if (!bubble) return
  tl.to(bubble, { autoAlpha: 0, y: 8, duration: 0.24, ease: 'power2.in' }, position)
}

function moveSlotToOrbit(tl, slot, index, radiusPx, position) {
  const orbit = orbitOffset(index, radiusPx)
  tl.to(
    slot,
    {
      x: orbit.x,
      y: orbit.y,
      zIndex: 3,
      duration: 0.55,
      ease: 'power3.inOut',
    },
    position,
  )
}

/**
 * Hero orbit: 排队飞入 → 居中放大整张卡 → 归位 → 转圈 + 仅台词循环（不停转）
 */
export function useHeroOrbitIntro(rootRef, options = {}) {
  let ctx
  let loopTl
  let spinTl
  let bobTweens = []

  function applyOrbitCssVars(slots) {
    const radiusPx = readOrbitRadiusPx(rootRef?.value)
    slots.forEach((slot, index) => {
      const { x, y } = orbitOffset(index, radiusPx)
      slot.style.setProperty('--orbit-x', `${x}px`)
      slot.style.setProperty('--orbit-y', `${y}px`)
    })
  }

  function finishIntro(carousel, slots) {
    applyOrbitCssVars(slots)
    gsap.set(slots, { clearProps: 'transform,x,y,zIndex,rotation,opacity,scale' })
    slots.forEach((slot) => {
      slot.classList.remove('is-orbit-spotlight')
      const face = slot.querySelector('.hero-orbit__card-face')
      if (face) gsap.set(face, { clearProps: 'transform,x,y,rotation' })
      slot.querySelectorAll('.hero-orbit__card, .hero-orbit__bubble').forEach((el) => {
        gsap.set(el, { clearProps: 'all' })
      })
    })
    carousel.classList.remove('is-intro')
    carousel.classList.add('is-spinning')
    options.onSpinStart?.()
  }

  function killSpinAnimations() {
    spinTl?.kill()
    spinTl = null
    bobTweens.forEach((tw) => tw.kill())
    bobTweens = []
  }

  /** 转盘旋转 + 卡面反向旋转保持正立 + 轻微上下浮动（全部由 GSAP 驱动） */
  function startGsapSpin(carousel, slots) {
    killSpinAnimations()

    const faces = slots
      .map((slot) => slot.querySelector('.hero-orbit__card-face'))
      .filter(Boolean)

    gsap.set(carousel, {
      rotation: 0,
      transformOrigin: '50% 50%',
      force3D: true,
    })
    gsap.set(faces, {
      rotation: 0,
      transformOrigin: '50% 50%',
      y: 0,
      force3D: true,
    })

    spinTl = gsap.timeline({ repeat: -1, defaults: { ease: 'none' } })
    spinTl.to(carousel, { rotation: 360, duration: SPIN_DURATION }, 0)
    spinTl.to(faces, { rotation: -360, duration: SPIN_DURATION }, 0)

    bobTweens = faces.map((face, index) => {
      const delay = parseFloat(
        getComputedStyle(face.closest('.hero-orbit__slot')).getPropertyValue('--float-delay'),
      )
      return gsap.to(face, {
        y: -4,
        duration: BOB_DURATION / 2,
        repeat: -1,
        yoyo: true,
        ease: 'sine.inOut',
        delay: Number.isFinite(delay) ? delay : index * 0.15,
      })
    })
  }

  /** 转圈时只播台词，不放大卡片；转盘不暂停 */
  function startDialogueLoop(slots, carousel) {
    carousel.classList.add('is-cycling-dialogue', 'is-showcasing')

    loopTl = gsap.timeline({ repeat: -1, repeatDelay: 0.25 })

    slots.forEach((slot) => {
      const bubble = slot.querySelector('.hero-orbit__bubble')

      loopTl.add(() => {
        slots.forEach((s) => s.classList.remove('is-orbit-spotlight'))
        slot.classList.add('is-orbit-spotlight')
      })

      dimOthersCards(loopTl, slots, slots.indexOf(slot), '>')
      showBubble(loopTl, bubble, '>-0.04')
      loopTl.to({}, { duration: CYCLE_HOLD }, '>')
      hideBubble(loopTl, bubble, '>')
      resetAllSlots(loopTl, slots, '>')
      loopTl.add(() => slot.classList.remove('is-orbit-spotlight'), '>')
    })
  }

  function runIntro(root) {
    const carousel = root.querySelector('.hero-orbit__carousel')
    const slots = gsap.utils.toArray(root.querySelectorAll('.hero-orbit__slot'))
    const ring = root.querySelector('.hero-orbit__ring')
    const aura = root.querySelector('.hero-orbit__aura')
    const radiusPx = readOrbitRadiusPx(root)
    const spot = spotlightOffset()

    if (!carousel || slots.length === 0) return

    carousel.classList.add('is-intro')
    carousel.classList.remove('is-spinning', 'is-cycling-dialogue')
    applyOrbitCssVars(slots)

    slots.forEach((slot, index) => {
      const face = slot.querySelector('.hero-orbit__card-face')
      prepareSlotForCartesian(slot, face)
      gsap.set(slot, {
        x: entryOffset(index).x,
        y: entryOffset(index).y,
        autoAlpha: 0,
        zIndex: 1 + index,
      })
    })

    const tl = gsap.timeline({ delay: 0.75, defaults: { ease: 'power3.out' } })

    if (aura) tl.from(aura, { scale: 0.6, autoAlpha: 0, duration: 0.6 }, 0)
    if (ring) tl.from(ring, { scale: 0.85, autoAlpha: 0, duration: 0.5, ease: 'power2.out' }, 0.05)

    tl.addLabel('queueIn', 0.12)
    slots.forEach((slot, index) => {
      const q = queueOffset(index)
      tl.to(
        slot,
        { x: q.x, y: q.y, autoAlpha: 1, duration: 0.72, ease: 'power3.out' },
        `queueIn+=${index * 0.11}`,
      )
    })

    tl.addLabel('showcase', '+=0.25')
    carousel.classList.add('is-showcasing')

    slots.forEach((slot, index) => {
      const card = slot.querySelector('.hero-orbit__card')
      const bubble = slot.querySelector('.hero-orbit__bubble')
      const label = `spot-${index}`
      const holdAt = 0.22
      const afterHold = holdAt + SHOWCASE_HOLD

      tl.addLabel(label, `showcase+=${index * SHOWCASE_STEP}`)

      tl.to(
        slot,
        { x: spot.x, y: spot.y, zIndex: 28, duration: 0.42, ease: 'power3.out' },
        label,
      )

      dimOthersCards(tl, slots, index, label)
      spotlightCard(tl, card, `${label}+=0.08`)
      showBubble(tl, bubble, `${label}+=0.14`)
      tl.to({}, { duration: SHOWCASE_HOLD }, `${label}+=${holdAt}`)
      hideBubble(tl, bubble, `${label}+=${afterHold}`)
      resetCard(tl, card, `${label}+=${afterHold - 0.02}`)
      moveSlotToOrbit(tl, slot, index, radiusPx, `${label}+=${afterHold + 0.06}`)
    })

    resetAllSlots(tl, slots, '+=0.1')

    tl.add('spinStart', '+=0.25')
    tl.call(
      () => {
        carousel.classList.remove('is-showcasing')
        finishIntro(carousel, slots)
        startGsapSpin(carousel, slots)
        startDialogueLoop(slots, carousel)
      },
      null,
      'spinStart',
    )
  }

  onMounted(async () => {
    await nextTick()
    const root = rootRef?.value
    if (!root) return

    if (prefersReducedMotion()) {
      const carousel = root.querySelector('.hero-orbit__carousel')
      const slots = gsap.utils.toArray(root.querySelectorAll('.hero-orbit__slot'))
      applyOrbitCssVars(slots)
      carousel?.classList.add('is-spinning')
      options.onSpinStart?.()
      return
    }

    ctx = gsap.context(() => runIntro(root), root)
  })

  onBeforeUnmount(() => {
    loopTl?.kill()
    loopTl = null
    killSpinAnimations()
    ctx?.revert()
    const carousel = rootRef?.value?.querySelector('.hero-orbit__carousel')
    carousel?.classList.remove(
      'is-intro',
      'is-showcasing',
      'is-spinning',
      'is-cycling-dialogue',
    )
  })
}
