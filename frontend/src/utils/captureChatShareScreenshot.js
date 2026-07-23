import { getElectronAPI } from '@/utils/electron'

const CAPTURE_PADDING = 16
const FRAME_WAIT_MS = 50

function wait(ms) {
  return new Promise((resolve) => window.setTimeout(resolve, ms))
}

async function waitForPaint(frames = 2) {
  for (let i = 0; i < frames; i += 1) {
    await new Promise((resolve) => requestAnimationFrame(resolve))
  }
  await wait(FRAME_WAIT_MS)
}

function roundRect(rect) {
  return {
    x: Math.round(rect.x),
    y: Math.round(rect.y),
    width: Math.max(1, Math.round(rect.width)),
    height: Math.max(1, Math.round(rect.height))
  }
}

function unionElementRects(elements, padding = CAPTURE_PADDING) {
  let left = Infinity
  let top = Infinity
  let right = -Infinity
  let bottom = -Infinity
  for (const el of elements) {
    const r = el.getBoundingClientRect()
    left = Math.min(left, r.left)
    top = Math.min(top, r.top)
    right = Math.max(right, r.right)
    bottom = Math.max(bottom, r.bottom)
  }
  return roundRect({
    x: left - padding,
    y: top - padding,
    width: (right - left) + padding * 2,
    height: (bottom - top) + padding * 2
  })
}

function getContentBounds(elements, container, padding = CAPTURE_PADDING) {
  const tops = elements.map((el) => el.offsetTop)
  const bottoms = elements.map((el) => el.offsetTop + el.offsetHeight)
  const top = Math.max(0, Math.min(...tops) - padding)
  const bottom = Math.min(container.scrollHeight, Math.max(...bottoms) + padding)
  return { top, bottom, height: Math.max(1, bottom - top) }
}

async function captureRegion(rect) {
  const ea = getElectronAPI()
  if (!ea || typeof ea.capturePageRegion !== 'function') {
    throw new Error('请在桌面客户端分享对话')
  }
  const res = await ea.capturePageRegion(roundRect(rect))
  if (!res?.ok || !res.dataUrl) {
    throw new Error('截屏失败，请稍后再试')
  }
  return res
}

function loadImage(dataUrl) {
  return new Promise((resolve, reject) => {
    const img = new Image()
    img.onload = () => resolve(img)
    img.onerror = () => reject(new Error('截屏图片解码失败'))
    img.src = dataUrl
  })
}

async function stitchImages(images) {
  const width = Math.max(...images.map((img) => img.width))
  const height = images.reduce((sum, img) => sum + img.height, 0)
  const canvas = document.createElement('canvas')
  canvas.width = width
  canvas.height = height
  const ctx = canvas.getContext('2d')
  if (!ctx) throw new Error('截屏合成失败')
  let y = 0
  for (const img of images) {
    ctx.drawImage(img, 0, y)
    y += img.height
  }
  return new Promise((resolve, reject) => {
    canvas.toBlob((blob) => {
      if (!blob) {
        reject(new Error('截屏编码失败'))
        return
      }
      resolve(blob)
    }, 'image/png', 0.92)
  })
}

function collectSelectedElements(container, selectedIds) {
  const ids = new Set([...selectedIds].map((id) => String(id)))
  return [...container.querySelectorAll('.gal-log__item[data-msg-id]')]
    .filter((el) => ids.has(el.dataset.msgId))
    .sort((a, b) => Number(a.dataset.msgId) - Number(b.dataset.msgId))
}

/**
 * Capture selected chat bubbles from the live chat page (Electron native capture).
 */
export async function captureChatShareScreenshot(container, selectedIds) {
  if (!container || !selectedIds?.size) {
    throw new Error('没有可分享的消息')
  }

  const elements = collectSelectedElements(container, selectedIds)
  if (!elements.length) {
    throw new Error('没有可分享的消息')
  }

  const previousScrollTop = container.scrollTop
  document.documentElement.classList.add('is-capturing-share')

  try {
    await waitForPaint()

    const bounds = getContentBounds(elements, container)
    const viewHeight = container.clientHeight

    if (bounds.height <= viewHeight) {
      container.scrollTop = bounds.top
      await waitForPaint()
      const rect = unionElementRects(elements)
      const shot = await captureRegion(rect)
      const res = await fetch(shot.dataUrl)
      return res.blob()
    }

    const containerRect = container.getBoundingClientRect()
    const slices = []
    let contentY = bounds.top

    while (contentY < bounds.bottom) {
      container.scrollTop = Math.max(0, contentY - CAPTURE_PADDING)
      await waitForPaint()

      const visibleTop = container.scrollTop
      const visibleBottom = visibleTop + viewHeight
      const sliceTop = Math.max(bounds.top, visibleTop)
      const sliceBottom = Math.min(bounds.bottom, visibleBottom)
      if (sliceBottom <= sliceTop) {
        contentY += viewHeight
        continue
      }

      const shot = await captureRegion(roundRect({
        x: containerRect.left,
        y: containerRect.top,
        width: containerRect.width,
        height: containerRect.height
      }))

      const dpr = shot.height / containerRect.height
      const sourceY = Math.max(0, Math.round((sliceTop - visibleTop) * dpr))
      const sourceHeight = Math.max(1, Math.round((sliceBottom - sliceTop) * dpr))

      slices.push(await loadImage(shot.dataUrl).then((img) => {
        const canvas = document.createElement('canvas')
        canvas.width = img.width
        canvas.height = sourceHeight
        const ctx = canvas.getContext('2d')
        ctx.drawImage(
          img,
          0,
          sourceY,
          img.width,
          sourceHeight,
          0,
          0,
          img.width,
          sourceHeight
        )
        return canvas
      }))

      contentY += viewHeight
    }

    if (!slices.length) {
      throw new Error('截屏失败，请稍后再试')
    }

    const images = await Promise.all(slices.map((canvas) => new Promise((resolve) => {
      const img = new Image()
      img.onload = () => resolve(img)
      img.src = canvas.toDataURL('image/png')
    })))

    return stitchImages(images)
  } finally {
    container.scrollTop = previousScrollTop
    document.documentElement.classList.remove('is-capturing-share')
  }
}
