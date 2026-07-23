import { createApp, nextTick } from 'vue'
import html2canvas from 'html2canvas'
import ChatShareSnapshot from '@/components/community/ChatShareSnapshot.vue'

function waitForImages(root, timeoutMs = 8000) {
  const images = [...root.querySelectorAll('img')]
  if (!images.length) return Promise.resolve()

  return new Promise((resolve) => {
    let pending = images.length
    let settled = false

    const finish = () => {
      if (settled) return
      settled = true
      resolve()
    }

    const timer = window.setTimeout(finish, timeoutMs)

    const done = () => {
      pending -= 1
      if (pending <= 0) {
        window.clearTimeout(timer)
        finish()
      }
    }

    for (const img of images) {
      if (img.complete) {
        done()
      } else {
        img.addEventListener('load', done, { once: true })
        img.addEventListener('error', done, { once: true })
      }
    }
  })
}

/**
 * Render selected chat messages into a PNG blob (offscreen DOM + html2canvas).
 */
export async function renderChatShareSnapshot(draft = {}) {
  const container = document.createElement('div')
  container.setAttribute('aria-hidden', 'true')
  container.style.position = 'fixed'
  container.style.left = '-100000px'
  container.style.top = '0'
  container.style.zIndex = '-1'
  container.style.pointerEvents = 'none'
  document.body.appendChild(container)

  const app = createApp(ChatShareSnapshot, {
    messages: draft.messages || [],
    characterName: draft.characterName || '角色',
    characterAvatarUrl: draft.characterAvatarUrl || '',
    userLabel: draft.userLabel || '我',
    userAvatarUrl: draft.userAvatarUrl || '',
    title: draft.title || '对话分享'
  })

  try {
    app.mount(container)
    await nextTick()
    await waitForImages(container)

    const target = container.firstElementChild
    if (!target) {
      throw new Error('snapshot mount failed')
    }

    const canvas = await html2canvas(target, {
      backgroundColor: '#0a0a12',
      scale: Math.min(2, window.devicePixelRatio || 1.5),
      useCORS: true,
      logging: false,
      ignoreElements: (element) => element.tagName === 'LINK' || element.tagName === 'STYLE'
    })

    return await new Promise((resolve, reject) => {
      canvas.toBlob((blob) => {
        if (!blob) {
          reject(new Error('snapshot encode failed'))
          return
        }
        resolve(blob)
      }, 'image/png', 0.92)
    })
  } finally {
    app.unmount()
    container.remove()
  }
}
