/**
 * 记忆星图 · 相机与几何（纯函数，node 环境可单测）
 *
 * 从组件里拆出来的原因：投影算错的表现是「整张图跑出画布」，
 * 那种 bug 只能靠眼睛发现，代价极高；把数学抽成纯函数就能断言。
 * 这里不碰 canvas，也不碰 Vue。
 */

/** 焦距：越小透视越夸张；1250 在 600px 高度下接近「微广角」，球体不会变形 */
export const CAMERA_FOV = 1250

export const CAMERA_LIMITS = {
  minZoom: 0.55,
  maxZoom: 2.6,
  maxPitch: 1.15,
  minPitch: -1.15
}

export function createCamera(overrides = {}) {
  return {
    yaw: -0.42,
    pitch: 0.14,
    zoom: 1,
    panX: 0,
    panY: 0,
    // 宽屏时把整团光点横向摊开（见 frameGraph），球体不变形、只是变成椭圆壳
    spreadX: 1,
    ...overrides
  }
}

export function clamp(value, min, max) {
  return Math.min(max, Math.max(min, value))
}

/**
 * 3D 内容实际只铺到 x∈[-300,300]、y∈[-260,260]，装进画布需要一个缩放基准。
 * 取宽高两个方向的较小约束：画布宽 1060 时基准约 1.55，脑区直径约 70px，
 * 整团光点占宽 55% 左右 —— 再大就会顶到图例和时间轴。
 */
export function fitBase(width, height) {
  if (!width || !height) return 1
  const base = Math.min(width / 620, height / 540) * 1.02
  return clamp(base, 0.7, 2.2)
}

/**
 * 世界坐标 → 屏幕坐标。
 * 旋转顺序：先绕 Y 轴（yaw），再绕 X 轴（pitch），最后做一次透视除法。
 */
export function projectPoint(point, camera, viewport) {
  const { yaw = 0, pitch = 0, zoom = 1, panX = 0, panY = 0, spreadX = 1 } = camera || {}
  const { width = 0, height = 0, base = 1 } = viewport || {}
  const cosYaw = Math.cos(yaw)
  const sinYaw = Math.sin(yaw)
  const cosPitch = Math.cos(pitch)
  const sinPitch = Math.sin(pitch)

  const [px = 0, py = 0, pz = 0] = point || []
  const x1 = px * cosYaw + pz * sinYaw
  const z1 = -px * sinYaw + pz * cosYaw
  const y1 = py * cosPitch - z1 * sinPitch
  const z2 = py * sinPitch + z1 * cosPitch
  // 横向摊开放在旋转之后：只拉宽屏幕上的分布，不改深度，也不影响前后遮挡
  const x2 = x1 * spreadX

  // 转到相机背后时 F + z 会趋近 0 甚至变负，scale 一旦为负，画椭圆就会抛
  // IndexSizeError（半径必须非负）。这里直接把背后半空间判为不可见。
  const denom = CAMERA_FOV + z2
  const inFront = denom > 90
  const scale = inFront ? (CAMERA_FOV / denom) * base * zoom : 0
  return {
    x: width / 2 + x2 * scale + panX,
    // 注意：本项目的 3D 坐标系约定是「y 向下为正」（屏幕坐标系），
    // 所以这里用 + y1 * scale，而不是标准 3D 图形的 - y1 * scale。
    y: height / 2 + y1 * scale + panY,
    scale,
    depth: z2,
    visible: inFront && scale > 0.02
  }
}

/** 远的暗、近的亮：z∈[-300,300] 映射到 [0.42, 1] */
export function depthFade(depth = 0) {
  return 1 - clamp((depth + 260) / 560, 0, 1) * 0.58
}

/** 二次贝塞尔取点；控制点由 flowControlPoint 生成 */
export function quadraticPoint(p0, control, p1, t) {
  const u = 1 - t
  const a = u * u
  const b = 2 * u * t
  const c = t * t
  return [
    a * p0[0] + b * control[0] + c * p1[0],
    a * p0[1] + b * control[1] + c * p1[1],
    a * p0[2] + b * control[2] + c * p1[2]
  ]
}

/**
 * 连线控制点：中点向上抬一点、朝观察者推一点。
 * 抬升量随距离增长，长连线才有明显的弧，短连线不会被拉成怪形状。
 */
export function flowControlPoint(from, to) {
  const distance = Math.hypot(to[0] - from[0], to[1] - from[1], to[2] - from[2])
  return [
    (from[0] + to[0]) / 2,
    (from[1] + to[1]) / 2 + distance * 0.2 + 12,
    (from[2] + to[2]) / 2 + distance * 0.1 + 16
  ]
}

export function sampleCurve(from, to, steps = 16) {
  const control = flowControlPoint(from, to)
  const points = []
  for (let i = 0; i <= steps; i += 1) {
    points.push(quadraticPoint(from, control, to, i / steps))
  }
  return points
}

/** 黄金角球面点阵：勾出半透明「脑壳」，不依赖任何随机数 */
export const SHELL_DOTS = (() => {
  const count = 260
  const golden = Math.PI * (3 - Math.sqrt(5))
  const dots = []
  for (let i = 0; i < count; i += 1) {
    const y = 1 - (i / (count - 1)) * 2
    const radius = Math.sqrt(Math.max(0, 1 - y * y))
    const theta = golden * i
    dots.push([Math.cos(theta) * radius * 300, y * 260, Math.sin(theta) * radius * 240])
  }
  return dots
})()

/** 三条纬线，作为脑壳的「经度」参照，比纯点阵更有结构感 */
export const SHELL_RINGS = (() => {
  const rings = []
  for (const ratio of [-0.62, 0, 0.62]) {
    const radius = Math.sqrt(Math.max(0, 1 - ratio * ratio))
    const points = []
    for (let i = 0; i <= 72; i += 1) {
      const angle = (i / 72) * Math.PI * 2
      points.push([Math.cos(angle) * 214 * radius, ratio * 172, Math.sin(angle) * 168 * radius])
    }
    rings.push(points)
  }
  return rings
})()

/** 拖拽 → 相机增量（dragging 时禁止继续自转，避免手感打架） */
export function applyDrag(camera, dx, dy) {
  return {
    ...camera,
    yaw: camera.yaw + dx * 0.0052,
    pitch: clamp(camera.pitch - dy * 0.0045, CAMERA_LIMITS.minPitch, CAMERA_LIMITS.maxPitch)
  }
}

/** 滚轮 → 缩放，按当前缩放比例减速，越近越难推 */
export function applyWheel(camera, deltaY) {
  const factor = Math.exp(-deltaY * 0.0012)
  return {
    ...camera,
    zoom: clamp(camera.zoom * factor, CAMERA_LIMITS.minZoom, CAMERA_LIMITS.maxZoom)
  }
}

/** 屏幕坐标拾取：距离优先，其次按权重放大命中区（光点比脑区难点中） */
export function pickNearest(pickables, x, y) {
  let best = null
  let bestScore = Infinity
  for (const item of pickables || []) {
    if (!item || item.hitRadius <= 0) continue
    const dx = item.x - x
    const dy = item.y - y
    const distance = Math.hypot(dx, dy)
    if (distance > item.hitRadius) continue
    const score = distance / Math.max(1, item.weight || 1)
    if (score < bestScore) {
      bestScore = score
      best = item
    }
  }
  return best
}
