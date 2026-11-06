import { describe, expect, it } from 'vitest'
import {
  CAMERA_FOV,
  CAMERA_LIMITS,
  SHELL_DOTS,
  SHELL_RINGS,
  applyDrag,
  applyWheel,
  clamp,
  createCamera,
  depthFade,
  fitBase,
  flowControlPoint,
  pickNearest,
  projectPoint,
  sampleCurve
} from '@/components/memory/brainProjection'
import { MEMORY_REGIONS } from '@/components/memory/memoryBrainModel'

const VIEWPORT = { width: 900, height: 560, base: fitBase(900, 560) }

describe('fitBase', () => {
  it('随画布变大而变大，但有上下限', () => {
    expect(fitBase(900, 560)).toBeGreaterThan(fitBase(660, 480))
    expect(fitBase(10, 10)).toBe(0.7)
    expect(fitBase(9000, 9000)).toBe(2.2)
  })

  it('没有尺寸时退回 1，不会算出 NaN/Infinity', () => {
    expect(fitBase(0, 0)).toBe(1)
  })
})

describe('projectPoint', () => {
  it('原点落在画布中心', () => {
    const point = projectPoint([0, 0, 0], createCamera({ yaw: 0, pitch: 0 }), VIEWPORT)
    expect(point.x).toBeCloseTo(VIEWPORT.width / 2)
    expect(point.y).toBeCloseTo(VIEWPORT.height / 2)
    expect(point.scale).toBeCloseTo(VIEWPORT.base)
    expect(point.visible).toBe(true)
  })

  it('越远透视越小：z 增大时 scale 下降', () => {
    const near = projectPoint([0, 0, -200], createCamera(), VIEWPORT)
    const far = projectPoint([0, 0, 200], createCamera(), VIEWPORT)
    expect(near.scale).toBeGreaterThan(far.scale)
    expect(near.depth).toBeLessThan(far.depth)
  })

  it('缩放翻倍时屏幕偏移也翻倍', () => {
    const camera = createCamera({ yaw: 0, pitch: 0 })
    const single = projectPoint([100, 0, 0], camera, VIEWPORT)
    const doubled = projectPoint([100, 0, 0], { ...camera, zoom: 2 }, VIEWPORT)
    expect(doubled.x - VIEWPORT.width / 2).toBeCloseTo((single.x - VIEWPORT.width / 2) * 2)
  })

  it('默认相机下所有脑区都投影在画布内（含 10% 余量）', () => {
    const camera = createCamera()
    for (const region of MEMORY_REGIONS) {
      const point = projectPoint(region.c, camera, VIEWPORT)
      expect(point.visible).toBe(true)
      expect(point.x).toBeGreaterThan(VIEWPORT.width * -0.1)
      expect(point.x).toBeLessThan(VIEWPORT.width * 1.1)
      expect(point.y).toBeGreaterThan(VIEWPORT.height * -0.1)
      expect(point.y).toBeLessThan(VIEWPORT.height * 1.1)
    }
  })
})

describe('相机边界', () => {
  it('拖拽不会把俯仰推出上下限', () => {
    let camera = createCamera()
    for (let i = 0; i < 200; i += 1) camera = applyDrag(camera, 0, -40)
    expect(camera.pitch).toBeLessThanOrEqual(CAMERA_LIMITS.maxPitch)
    for (let i = 0; i < 400; i += 1) camera = applyDrag(camera, 0, 40)
    expect(camera.pitch).toBeGreaterThanOrEqual(CAMERA_LIMITS.minPitch)
  })

  it('滚轮缩放夹在 minZoom..maxZoom', () => {
    let camera = createCamera()
    for (let i = 0; i < 60; i += 1) camera = applyWheel(camera, -200)
    expect(camera.zoom).toBeCloseTo(CAMERA_LIMITS.maxZoom)
    for (let i = 0; i < 120; i += 1) camera = applyWheel(camera, 200)
    expect(camera.zoom).toBeCloseTo(CAMERA_LIMITS.minZoom)
  })
})

describe('曲线与外壳', () => {
  it('sampleCurve 首尾落在两端，点数 = steps + 1', () => {
    const curve = sampleCurve([0, 0, 0], [100, 0, 0], 8)
    expect(curve).toHaveLength(9)
    expect(curve[0]).toEqual([0, 0, 0])
    expect(curve[8]).toEqual([100, 0, 0])
  })

  it('控制点在中点抬起，曲线不会贴着直线走', () => {
    const control = flowControlPoint([0, 0, 0], [100, 0, 0])
    expect(control[1]).toBeGreaterThan(0)
    expect(control[2]).toBeGreaterThan(0)
  })

  it('脑壳点阵 / 纬线都是有限数值，可直接投影', () => {
    expect(SHELL_DOTS.length).toBeGreaterThan(200)
    expect(SHELL_RINGS).toHaveLength(3)
    const camera = createCamera()
    for (const dot of SHELL_DOTS.slice(0, 40)) {
      const point = projectPoint(dot, camera, VIEWPORT)
      expect(Number.isFinite(point.x)).toBe(true)
      expect(Number.isFinite(point.y)).toBe(true)
    }
  })
})

describe('pickNearest', () => {
  const pickables = [
    { id: 'a', x: 100, y: 100, hitRadius: 20, weight: 1 },
    { id: 'b', x: 112, y: 100, hitRadius: 20, weight: 4 }
  ]

  it('命中区内取最近的；权重高的允许更「宽松」', () => {
    expect(pickNearest(pickables, 110, 100).id).toBe('b')
    expect(pickNearest(pickables, 100, 100).id).toBe('a')
  })

  it('点在外面返回 null', () => {
    expect(pickNearest(pickables, 400, 400)).toBeNull()
    expect(pickNearest([], 0, 0)).toBeNull()
  })
})

describe('depthFade / clamp', () => {
  it('越深越暗，且落在 0.42..1', () => {
    expect(depthFade(-260)).toBeCloseTo(1)
    expect(depthFade(300)).toBeCloseTo(0.42)
    expect(clamp(5, 0, 1)).toBe(1)
    expect(clamp(-5, 0, 1)).toBe(0)
  })

  it('FOV 是正数，透视除法不会翻转', () => {
    expect(CAMERA_FOV).toBeGreaterThan(0)
  })

  it('把点转到相机背后时不给负缩放（画布椭圆半径必须非负）', () => {
    const camera = { ...createCamera(), pitch: 0, yaw: 0, zoom: 1 }
    const viewport = { width: 900, height: 560, base: 1 }
    // 相机在 -z 方向看，这个点被推到相机后面
    const behind = projectPoint([0, 0, -4000], camera, viewport)
    expect(behind.visible).toBe(false)
    expect(behind.scale).toBe(0)
    expect(Number.isFinite(behind.x)).toBe(true)
  })
})
