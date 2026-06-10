import { nextTick, onMounted, onUnmounted, watch } from 'vue'
import { gsap } from 'gsap'
import { isSquareCommentMine } from '@/utils/squareComment'

const LANES = [2, 22]
const MIN_GAP_SEC = 3

const DANMAKU_BASE_STYLE = {
  position: 'absolute',
  top: '0',
  left: '0',
  maxWidth: '70%',
  padding: '2px 8px',
  borderRadius: '999px',
  fontSize: '11px',
  lineHeight: '1.35',
  whiteSpace: 'nowrap',
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  backdropFilter: 'blur(4px)',
  willChange: 'transform',
}

const DANMAKU_DEFAULT_LOOK = {
  color: 'rgba(232, 237, 242, 0.82)',
  fontWeight: '400',
  background: 'rgba(10, 10, 16, 0.72)',
  boxShadow: '0 1px 4px rgba(0, 0, 0, 0.18)',
  textShadow: 'none',
  border: '1px solid transparent',
}

const DANMAKU_MINE_LOOK = {
  color: '#1a1a24',
  fontWeight: '700',
  background: 'linear-gradient(135deg, #ffd866 0%, #f5b042 100%)',
  boxShadow: '0 0 14px rgba(245, 176, 66, 0.55), 0 1px 4px rgba(0, 0, 0, 0.28)',
  textShadow: '0 1px 0 rgba(255, 255, 255, 0.35)',
  border: '1px solid rgba(255, 216, 102, 0.85)',
}

function applyDanmakuLook(el, isMine) {
  Object.assign(el.style, DANMAKU_BASE_STYLE, isMine ? DANMAKU_MINE_LOOK : DANMAKU_DEFAULT_LOOK)
}

/**
 * GSAP 弹幕：在卡片 body 内从右向左滚动，本人评语高亮。
 * @param {import('vue').Ref<HTMLElement|null>} layerRef 弹幕容器
 * @param {import('vue').Ref<Array<{id:number, content:string, isMine?:boolean}>>} commentsRef
 * @param {import('vue').Ref<number|string|null|undefined>} viewerUserIdRef
 */
export function useSquareDanmaku(layerRef, commentsRef, viewerUserIdRef = null) {
  let ctx = null
  let reducedMotion = false

  function resolveMine(item) {
    const viewerUserId = viewerUserIdRef?.value ?? null
    return isSquareCommentMine(item, viewerUserId)
  }

  function clearAnimations() {
    ctx?.revert()
    ctx = null
    const layer = layerRef.value
    if (layer) {
      layer.innerHTML = ''
    }
  }

  function createDanmakuElement(item, index) {
    const el = document.createElement('span')
    const isMine = resolveMine(item)
    el.className = `danmaku-item${isMine ? ' danmaku-item--mine' : ''}`
    el.textContent = item.content
    applyDanmakuLook(el, isMine)
    if (isMine) {
      el.dataset.mine = '1'
    }
    return { el, isMine }
  }

  function buildStatic(layer, comments) {
    layer.innerHTML = ''
    comments.slice(0, 4).forEach((item, index) => {
      const { el } = createDanmakuElement(item, index)
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
        const { el, isMine } = createDanmakuElement(item, index)
        layer.appendChild(el)

        const lane = LANES[index % LANES.length]
        const textWidth = el.offsetWidth || 80
        const travel = width + textWidth + 16
        const duration = Math.max(6, Math.min(14, travel / 42))
        const startDelay = index * MIN_GAP_SEC + (isMine ? 0 : 0.6) + Math.random() * 1.2

        // 仅位移，不动画 opacity，避免随机「更白」误判
        gsap.fromTo(
          el,
          { x: width + 8, y: lane },
          {
            x: -textWidth - 8,
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
    () => [layerRef.value, commentsRef.value, viewerUserIdRef?.value],
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
