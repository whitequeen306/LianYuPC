import { gsap } from 'gsap'

const REDUCED_MOTION = typeof window !== 'undefined'
  && window.matchMedia('(prefers-reduced-motion: reduce)').matches

function ensureSheen(el) {
  if (el.querySelector('.card-tilt-sheen')) return
  const sheen = document.createElement('span')
  sheen.className = 'card-tilt-sheen'
  sheen.setAttribute('aria-hidden', 'true')
  sheen.style.cssText = [
    'position:absolute',
    'inset:0',
    'pointer-events:none',
    'border-radius:inherit',
    'opacity:0',
    'background:linear-gradient(120deg, transparent 35%, rgba(255,255,255,0.22) 50%, transparent 65%)',
    'transform:translate3d(-30%, -30%, 0)',
    'transition:opacity 0.25s ease',
    'z-index:2',
  ].join(';')
  el.appendChild(sheen)
}

function createState(el, binding) {
  const maxTilt = Number(binding?.value?.maxTilt) || 8
  const perspective = Number(binding?.value?.perspective) || 900

  el.style.transformStyle = 'preserve-3d'
  el.style.willChange = 'transform'
  if (getComputedStyle(el).position === 'static') {
    el.style.position = 'relative'
  }

  ensureSheen(el)
  const sheen = el.querySelector('.card-tilt-sheen')

  return {
    el,
    sheen,
    maxTilt,
    perspective,
    hover: false,
    tiltTween: null,
    sheenTween: null,
    onEnter: null,
    onMove: null,
    onLeave: null,
  }
}

function setTilt(state, clientX, clientY) {
  const rect = state.el.getBoundingClientRect()
  const px = (clientX - rect.left) / rect.width
  const py = (clientY - rect.top) / rect.height
  const rotateY = (px - 0.5) * state.maxTilt * 2
  const rotateX = (0.5 - py) * state.maxTilt * 2
  const sheenX = (px - 0.5) * 40
  const sheenY = (py - 0.5) * 40

  state.tiltTween?.kill()
  state.sheenTween?.kill()

  state.tiltTween = gsap.to(state.el, {
    rotateX,
    rotateY,
    transformPerspective: state.perspective,
    duration: 0.35,
    ease: 'power2.out',
    overwrite: true,
  })

  state.sheenTween = gsap.to(state.sheen, {
    x: sheenX,
    y: sheenY,
    opacity: 0.85,
    duration: 0.35,
    ease: 'power2.out',
    overwrite: true,
  })
}

function resetTilt(state) {
  state.tiltTween?.kill()
  state.sheenTween?.kill()
  state.tiltTween = gsap.to(state.el, {
    rotateX: 0,
    rotateY: 0,
    duration: 0.55,
    ease: 'power3.out',
    overwrite: true,
  })
  state.sheenTween = gsap.to(state.sheen, {
    opacity: 0,
    x: 0,
    y: 0,
    duration: 0.45,
    ease: 'power2.out',
    overwrite: true,
  })
}

function mountListeners(state) {
  state.onEnter = (e) => {
    state.hover = true
    setTilt(state, e.clientX, e.clientY)
  }

  state.onMove = (e) => {
    if (!state.hover) return
    setTilt(state, e.clientX, e.clientY)
  }

  state.onLeave = () => {
    state.hover = false
    resetTilt(state)
  }

  state.el.addEventListener('mouseenter', state.onEnter)
  state.el.addEventListener('mousemove', state.onMove)
  state.el.addEventListener('mouseleave', state.onLeave)
}

function unmountListeners(state) {
  state.el.removeEventListener('mouseenter', state.onEnter)
  state.el.removeEventListener('mousemove', state.onMove)
  state.el.removeEventListener('mouseleave', state.onLeave)
  state.tiltTween?.kill()
  state.sheenTween?.kill()
  gsap.set(state.el, { clearProps: 'transform' })
  state.sheen?.remove()
}

export const tiltDirective = {
  mounted(el, binding) {
    if (REDUCED_MOTION) return
    const state = createState(el, binding)
    mountListeners(state)
    el.__tiltState = state
  },

  updated(el, binding) {
    const state = el.__tiltState
    if (!state) return
    state.maxTilt = Number(binding?.value?.maxTilt) || 8
    state.perspective = Number(binding?.value?.perspective) || 900
  },

  unmounted(el) {
    const state = el.__tiltState
    if (!state) return
    unmountListeners(state)
    delete el.__tiltState
  },
}
