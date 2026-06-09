import { nextTick, onMounted, onUnmounted, watch } from 'vue'
import { gsap } from 'gsap'

const LANES = [2, 22]
const MIN_GAP_SEC = 3

/**
 * GSAP 弹幕：在卡片 body 内从右向左滚动，本人评语高亮。
 * @param {import('vue').Ref<HTMLElement|null>} layerRef 弹幕容器
 * @param {import('vue').Ref<Array<{id:number, content:string, isMine?:boolean}>>} commentsRef
 */
export function useSquareDanmaku(layerRef, commentsRef) {
  let ctx = null
  let reducedMotion = false

  function clearAnimations() {
    ctx?.revert()
    ctx = null
    const layer = layerRef.value
    if (layer) {
      layer.innerHTML = ''
    }
  }

  function buildStatic(layer, comments) {
    layer.innerHTML = ''
    comments.slice(0, 4).forEach((item, index) => {
      const el = document.createElement('span')
      el.className = `danmaku-item${item.isMine ? ' danmaku-item--mine' : ''}`
      el.textContent = item.content
      el.style.top = `${LANES[index % LANES.length]}px`
      el.style.left = `${8 + (index % 2) * 12}px`
      layer.appendChild(el)
    })
  }

  function buildAnimated(layer, comments) {
    clearAnimations()
    if (!comments.length) return

    const width = layer.clientWidth || 240
    ctx = gsap.context(() => {
      comments.forEach((item, index) => {
        const el = document.createElement('span')
        el.className = `danmaku-item${item.isMine ? ' danmaku-item--mine' : ''}`
        el.textContent = item.content
        layer.appendChild(el)

        const lane = LANES[index % LANES.length]
        const textWidth = el.offsetWidth || 80
        const travel = width + textWidth + 16
        const duration = Math.max(6, Math.min(14, travel / 42))
        const startDelay = index * MIN_GAP_SEC + (item.isMine ? 0 : 0.6) + Math.random() * 1.2

        gsap.fromTo(
          el,
          { x: width + 8, y: lane, opacity: 0 },
          {
            x: -textWidth - 8,
            opacity: 1,
            duration,
            delay: startDelay,
            ease: 'none',
            repeat: -1,
            repeatDelay: comments.length * MIN_GAP_SEC * 0.35 + Math.random() * 2,
          },
        )
      })
    }, layer)
  }

  function rebuild() {
    const layer = layerRef.value
    if (!layer) return
    const comments = commentsRef.value || []
    clearAnimations()
    if (!comments.length) return

    reducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches
    if (reducedMotion) {
      buildStatic(layer, comments)
      return
    }
    buildAnimated(layer, comments)
  }

  watch(
    () => [layerRef.value, commentsRef.value],
    () => {
      nextTick(() => rebuild())
    },
    { deep: true, flush: 'post' },
  )

  onMounted(() => {
    nextTick(() => rebuild())
  })

  onUnmounted(() => {
    clearAnimations()
  })

  return { rebuild, clearAnimations }
}
