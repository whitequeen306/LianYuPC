import { ref, unref, watch } from 'vue'

const SCROLL_UP_THRESHOLD = 80
const SCROLL_BOTTOM_THRESHOLD = 20
const SCROLL_TOP_LOAD_THRESHOLD = 120

/**
 * 聊天消息区滚动：用户上翻时不自动拉底，提供「回到底部」按钮；
 * 可选在滚到顶部时触发加载更早消息。
 *
 * 容器常在会话 id 就绪后才挂到 DOM（v-if），所以监听必须跟 ref 走，
 * 不能只在 onMounted 绑一次——那时列表往往还不存在。
 */
export function useChatScroll(containerRef, anchorRef, options = {}) {
  const isUserScrolledUp = ref(false)

  function distanceFromBottom(el) {
    if (!el) return 0
    return el.scrollHeight - el.scrollTop - el.clientHeight
  }

  function updateScrollState() {
    const el = containerRef.value
    if (!el) return
    const dist = distanceFromBottom(el)
    isUserScrolledUp.value = dist > SCROLL_UP_THRESHOLD
    if (dist <= SCROLL_BOTTOM_THRESHOLD) {
      isUserScrolledUp.value = false
    }
  }

  function maybeLoadOlder() {
    const el = containerRef.value
    if (!el || el.scrollTop > SCROLL_TOP_LOAD_THRESHOLD) return
    const hasMore = unref(options.hasMoreOlder)
    const loading = unref(options.loadingOlder)
    if (!hasMore || loading) return
    options.onReachTop?.()
  }

  watch(containerRef, (el, _prev, onCleanup) => {
    if (!el) return
    const onScroll = () => {
      updateScrollState()
      maybeLoadOlder()
    }
    el.addEventListener('scroll', onScroll, { passive: true })
    updateScrollState()
    onCleanup(() => {
      el.removeEventListener('scroll', onScroll)
    })
  }, { flush: 'post', immediate: true })

  function scrollToBottom({ force = false, behavior = 'smooth' } = {}) {
    if (!force && isUserScrolledUp.value) return
    const el = containerRef.value
    // Instant jump for enter-chat / "回到底部"; smooth only for live append while reading.
    if (behavior === 'auto' && el) {
      el.scrollTop = el.scrollHeight
    } else {
      anchorRef.value?.scrollIntoView({ behavior })
    }
    if (force) {
      isUserScrolledUp.value = false
    }
  }

  /** Enter conversation / button: snap to latest without animating from the top. */
  function jumpToBottom() {
    scrollToBottom({ force: true, behavior: 'auto' })
  }

  return {
    isUserScrolledUp,
    scrollToBottom,
    jumpToBottom,
    updateScrollState
  }
}

export function sleep(ms) {
  return new Promise(resolve => setTimeout(resolve, ms))
}

export const MIN_REPLY_DISPLAY_MS = 1500
