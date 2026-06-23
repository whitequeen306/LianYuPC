import { gsap } from 'gsap'

const REDUCED_MOTION = typeof window !== 'undefined'
  && window.matchMedia('(prefers-reduced-motion: reduce)').matches

function readAccentRgb() {
  const raw = getComputedStyle(document.documentElement).getPropertyValue('--ly-accent-rgb').trim()
  const parts = raw.split(',').map(v => Number.parseInt(v.trim(), 10))
  if (parts.length === 3 && parts.every(n => Number.isFinite(n))) return parts
  return [244, 166, 181]
}

function ensureShell(el) {
  if (el.dataset.bubbleReady === '1') return
  el.dataset.bubbleReady = '1'

  const position = getComputedStyle(el).position
  if (position === 'static' || !position) {
    el.style.position = 'relative'
  }
  el.style.overflow = 'hidden'
  el.style.isolation = 'isolate'

  const canvas = document.createElement('canvas')
  canvas.className = 'bubble-btn__canvas'
  canvas.setAttribute('aria-hidden', 'true')
  canvas.style.cssText = 'position:absolute;inset:0;width:100%;height:100%;pointer-events:none;z-index:0;border-radius:inherit;'
  el.prepend(canvas)

  const content = document.createElement('span')
  content.className = 'bubble-btn__content'
  content.style.cssText = 'position:relative;z-index:1;display:inline-flex;align-items:center;justify-content:center;gap:inherit;'
  while (el.firstChild && el.firstChild !== canvas) {
    content.appendChild(el.firstChild)
  }
  el.appendChild(content)
}

function createState(el) {
  const canvas = el.querySelector('.bubble-btn__canvas')
  const ctx = canvas.getContext('2d')
  const accent = readAccentRgb()

  const state = {
    el,
    canvas,
    ctx,
    accent,
    width: 0,
    height: 0,
    dpr: 1,
    hover: false,
    raf: 0,
    pointer: { x: 0, y: 0 },
    blob: { x: 0, y: 0, r: 0, targetR: 0 },
    particles: [],
    magneticTween: null,
    onEnter: null,
    onMove: null,
    onLeave: null,
    onClick: null,
    onResize: null,
  }

  return state
}

function resizeCanvas(state) {
  const rect = state.el.getBoundingClientRect()
  state.width = Math.max(rect.width, 1)
  state.height = Math.max(rect.height, 1)
  state.dpr = Math.min(window.devicePixelRatio || 1, 2)
  state.canvas.width = Math.floor(state.width * state.dpr)
  state.canvas.height = Math.floor(state.height * state.dpr)
  state.ctx.setTransform(state.dpr, 0, 0, state.dpr, 0, 0)
}

function localPoint(state, clientX, clientY) {
  const rect = state.el.getBoundingClientRect()
  return {
    x: clientX - rect.left,
    y: clientY - rect.top,
  }
}

function drawFrame(state) {
  const { ctx, width, height, blob, particles, accent, hover } = state
  ctx.clearRect(0, 0, width, height)

  if (hover) {
    blob.x += (state.pointer.x - blob.x) * 0.18
    blob.y += (state.pointer.y - blob.y) * 0.18
    blob.r += (blob.targetR - blob.r) * 0.12

    const [r, g, b] = accent
    const grad = ctx.createRadialGradient(blob.x, blob.y, blob.r * 0.1, blob.x, blob.y, blob.r)
    grad.addColorStop(0, `rgba(${r}, ${g}, ${b}, 0.34)`)
    grad.addColorStop(0.55, `rgba(${r}, ${g}, ${b}, 0.16)`)
    grad.addColorStop(1, `rgba(${r}, ${g}, ${b}, 0)`)
    ctx.fillStyle = grad
    ctx.beginPath()
    ctx.arc(blob.x, blob.y, blob.r, 0, Math.PI * 2)
    ctx.fill()
  }

  for (let i = particles.length - 1; i >= 0; i -= 1) {
    const p = particles[i]
    p.life -= 1
    p.x += p.vx
    p.y += p.vy
    p.vy -= 0.04
    p.r *= 0.985

    if (p.life <= 0 || p.r < 0.4) {
      particles.splice(i, 1)
      continue
    }

    const alpha = Math.min(1, p.life / 28)
    const [r, g, b] = accent
    ctx.fillStyle = `rgba(${r}, ${g}, ${b}, ${alpha * 0.75})`
    ctx.beginPath()
    ctx.arc(p.x, p.y, p.r, 0, Math.PI * 2)
    ctx.fill()
  }

  if (hover || particles.length > 0) {
    state.raf = requestAnimationFrame(() => drawFrame(state))
  } else {
    state.raf = 0
  }
}

function startLoop(state) {
  if (!state.raf) {
    state.raf = requestAnimationFrame(() => drawFrame(state))
  }
}

function stopLoop(state) {
  if (state.raf) {
    cancelAnimationFrame(state.raf)
    state.raf = 0
  }
}

function spawnSplash(state, x, y) {
  const count = 6 + Math.floor(Math.random() * 5)
  for (let i = 0; i < count; i += 1) {
    const angle = (-Math.PI / 2) + (Math.random() - 0.5) * 1.4
    const speed = 1.2 + Math.random() * 2.4
    state.particles.push({
      x,
      y,
      vx: Math.cos(angle) * speed,
      vy: Math.sin(angle) * speed,
      r: 2 + Math.random() * 3.5,
      life: 24 + Math.random() * 18,
    })
  }
  startLoop(state)
}

function applyMagnetic(state, clientX, clientY) {
  const rect = state.el.getBoundingClientRect()
  const cx = rect.left + rect.width / 2
  const cy = rect.top + rect.height / 2
  const dx = (clientX - cx) / rect.width
  const dy = (clientY - cy) / rect.height
  const max = 4
  state.magneticTween?.kill()
  state.magneticTween = gsap.to(state.el, {
    x: dx * max,
    y: dy * max,
    duration: 0.35,
    ease: 'power3.out',
    overwrite: true,
  })
}

function resetMagnetic(state) {
  state.magneticTween?.kill()
  state.magneticTween = gsap.to(state.el, {
    x: 0,
    y: 0,
    duration: 0.45,
    ease: 'power3.out',
    overwrite: true,
  })
}

function mountListeners(state) {
  state.onEnter = (e) => {
    state.hover = true
    resizeCanvas(state)
    const pt = localPoint(state, e.clientX, e.clientY)
    state.pointer = pt
    state.blob.x = pt.x
    state.blob.y = pt.y
    state.blob.r = 0
    state.blob.targetR = Math.max(state.width, state.height) * 0.55
    startLoop(state)
  }

  state.onMove = (e) => {
    if (!state.hover) return
    const pt = localPoint(state, e.clientX, e.clientY)
    state.pointer = pt
    applyMagnetic(state, e.clientX, e.clientY)
    startLoop(state)
  }

  state.onLeave = () => {
    state.hover = false
    state.blob.targetR = 0
    resetMagnetic(state)
    startLoop(state)
  }

  state.onClick = (e) => {
    resizeCanvas(state)
    const pt = localPoint(state, e.clientX, e.clientY)
    spawnSplash(state, pt.x, pt.y)
  }

  state.onResize = () => {
    if (state.hover) resizeCanvas(state)
  }

  state.el.addEventListener('mouseenter', state.onEnter)
  state.el.addEventListener('mousemove', state.onMove)
  state.el.addEventListener('mouseleave', state.onLeave)
  state.el.addEventListener('click', state.onClick)
  window.addEventListener('resize', state.onResize)
}

function unmountListeners(state) {
  state.el.removeEventListener('mouseenter', state.onEnter)
  state.el.removeEventListener('mousemove', state.onMove)
  state.el.removeEventListener('mouseleave', state.onLeave)
  state.el.removeEventListener('click', state.onClick)
  window.removeEventListener('resize', state.onResize)
  stopLoop(state)
  state.magneticTween?.kill()
  gsap.set(state.el, { clearProps: 'transform,x,y' })
}

export const bubbleBtnDirective = {
  mounted(el) {
    if (REDUCED_MOTION) return
    if (el.disabled || el.getAttribute('aria-disabled') === 'true') return

    ensureShell(el)
    const state = createState(el)
    mountListeners(state)
    el.__bubbleBtnState = state
  },

  unmounted(el) {
    const state = el.__bubbleBtnState
    if (!state) return
    unmountListeners(state)
    delete el.__bubbleBtnState
  },
}
