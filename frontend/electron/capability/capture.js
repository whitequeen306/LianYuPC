/**
 * 截屏采集：`desktopCapturer` → JPEG（不给模型 PNG，省 60% 带宽与 token）。
 *
 * 关键约束：
 * - **只抓主屏**（P1）。副屏显式报 `unsupported`，绝不「按主屏坐标点副屏」——
 *   那是最典型的系统性误点来源；
 * - 回传 `scaleFactor` 与逻辑尺寸：模型输出物理像素，换算需要这两者；
 * - 全黑帧（独占全屏/DRM/受保护内容）要识别出来，否则模型会对着黑图瞎猜。
 */

const ALLOWED_QUALITY = { min: 30, max: 90 }
const MIN_WIDTH = 480
const MAX_WIDTH = 1600

function clampQuality(value) {
  const n = Number(value)
  if (!Number.isFinite(n)) return 55
  return Math.min(ALLOWED_QUALITY.max, Math.max(ALLOWED_QUALITY.min, Math.round(n)))
}

function pickSource(sources, display) {
  if (!Array.isArray(sources) || sources.length === 0) return null
  const displayId = display?.id != null ? String(display.id) : ''
  const matched = displayId ? sources.find((s) => String(s.display_id) === displayId) : null
  return matched || sources[0]
}

/**
 * 黑帧判定：抽样统计亮度，几乎全黑/全同色即视为「看不到」。
 * 用抽样而不是全量，是为了不把 2MB 位图在 IPC 线程上算一遍。
 */
export function isBlankImage(image) {
  try {
    const bitmap = image.toBitmap()
    if (!bitmap || bitmap.length < 64) return false
    let sum = 0
    let sumSq = 0
    let count = 0
    const step = Math.max(4, Math.floor(bitmap.length / 4096 / 4) * 4)
    for (let i = 0; i + 2 < bitmap.length; i += step) {
      const luma = (bitmap[i] * 299 + bitmap[i + 1] * 587 + bitmap[i + 2] * 114) / 1000
      sum += luma
      sumSq += luma * luma
      count += 1
    }
    if (count === 0) return false
    const mean = sum / count
    const variance = Math.max(0, sumSq / count - mean * mean)
    return variance < 4 && mean < 24
  } catch {
    return false
  }
}

export function createScreenCapture({ desktopCapturer, screen, log = () => {} } = {}) {
  if (!desktopCapturer || !screen) throw new Error('screen capture: desktopCapturer/screen required')

  return {
    /**
     * @param {object} [options]
     * @param {number} [options.maxWidth=1024] 目标宽度（等比例缩放）
     * @param {number} [options.quality=55]    JPEG 质量
     * @param {number} [options.displayId]     指定显示器；缺省主屏
     */
    async capture({ maxWidth = 1024, quality = 55, displayId } = {}) {
      const displays = typeof screen.getAllDisplays === 'function' ? screen.getAllDisplays() : []
      const primary = typeof screen.getPrimaryDisplay === 'function' ? screen.getPrimaryDisplay() : null
      const target = displayId != null
        ? displays.find((d) => String(d.id) === String(displayId))
        : primary
      if (!target) {
        return { ok: false, error: 'unsupported', detail: 'display_not_found' }
      }
      if (primary && displayId != null && String(displayId) !== String(primary.id)) {
        // P1 明确只支持主屏：副屏坐标映射未验证，宁可不做
        return { ok: false, error: 'unsupported', detail: 'secondary_display' }
      }

      const bounds = target.bounds || { width: 1280, height: 720 }
      const width = Math.min(MAX_WIDTH, Math.max(MIN_WIDTH, Math.round(maxWidth)))
      const scale = Math.min(1, width / Math.max(1, bounds.width))
      const thumbW = Math.max(MIN_WIDTH, Math.round(bounds.width * scale))
      const thumbH = Math.max(240, Math.round(bounds.height * scale))

      let sources = []
      try {
        sources = await desktopCapturer.getSources({
          types: ['screen'],
          thumbnailSize: { width: thumbW, height: thumbH },
        })
      } catch (e) {
        log(`capture failed: ${e?.message || e}`)
        return { ok: false, error: 'capture_failed', detail: e?.message || 'getSources failed' }
      }

      const source = pickSource(sources, target)
      const image = source?.thumbnail
      if (!image || (typeof image.isEmpty === 'function' && image.isEmpty())) {
        return { ok: false, error: 'capture_failed', detail: 'empty_thumbnail' }
      }

      const size = typeof image.getSize === 'function' ? image.getSize() : { width: thumbW, height: thumbH }
      let jpeg
      try {
        jpeg = image.toJPEG(clampQuality(quality))
      } catch (e) {
        return { ok: false, error: 'capture_failed', detail: e?.message || 'toJPEG failed' }
      }
      const black = isBlankImage(image)

      return {
        ok: true,
        mime: 'image/jpeg',
        b64: jpeg.toString('base64'),
        w: size.width,
        h: size.height,
        scaleFactor: Number(target.scaleFactor) || 1,
        screen: { width: bounds.width, height: bounds.height, id: target.id },
        black,
      }
    },
  }
}
