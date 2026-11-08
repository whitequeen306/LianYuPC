<template>
  <div ref="rootRef" class="brain-map" :class="{ 'brain-map--compact': compact }">
    <div ref="stageRef" class="brain-map__stage" :style="stageStyle">
      <canvas
        ref="canvasRef"
        class="brain-map__canvas"
        tabindex="0"
        :aria-label="t('memory.brain.a11y')"
        @pointerdown="onPointerDown"
        @pointermove="onPointerMove"
        @pointerup="onPointerUp"
        @pointercancel="onPointerUp"
        @pointerleave="onPointerLeave"
        @dblclick="resetView"
        @keydown="onKeyDown"
      />

      <!-- 图例：点一下聚焦某个脑区，再点取消 -->
      <ul class="brain-legend">
        <li
          v-for="region in regions"
          :key="region.id"
          class="brain-legend__item"
          :class="{
            'is-active': focusedRegion === region.id,
            'is-dim': focusedRegion && focusedRegion !== region.id
          }"
          :style="{ '--dot': region.color }"
          @click="toggleFocus(region.id)"
        >
          <span class="brain-legend__dot" />
          <span class="brain-legend__label">{{ t(region.nameKey) }}</span>
          <span class="brain-legend__count">{{ region.count }}</span>
        </li>
      </ul>

      <div class="brain-tools">
        <button
          type="button"
          class="brain-tool"
          :class="{ 'is-on': autoRotate }"
          :title="t('memory.brain.autoRotate')"
          @click="toggleAutoRotate"
        >
          <el-icon :size="15"><Refresh /></el-icon>
        </button>
        <button type="button" class="brain-tool" :title="t('memory.brain.resetView')" @click="resetView">
          <el-icon :size="15"><Aim /></el-icon>
        </button>
        <button
          type="button"
          class="brain-tool"
          :class="{ 'is-on': replayPlaying }"
          :title="t('memory.brain.replay')"
          :disabled="!canReplay"
          @click="toggleReplay"
        >
          <el-icon :size="15"><VideoPause v-if="replayPlaying" /><VideoPlay v-else /></el-icon>
        </button>
      </div>

      <p class="brain-hint">{{ t('memory.brain.hint') }}</p>

      <div v-if="tooltip" class="brain-tip" :style="tooltipStyle">
        <span class="brain-tip__title">{{ tooltip.title }}</span>
        <span v-if="tooltip.meta" class="brain-tip__meta">{{ tooltip.meta }}</span>
        <span v-if="tooltip.hint" class="brain-tip__hint">{{ tooltip.hint }}</span>
      </div>

      <p v-if="!stats.total" class="brain-empty">{{ t('memory.brain.empty') }}</p>
    </div>

    <aside class="brain-panel">
      <div class="brain-search">
        <el-icon :size="14"><Search /></el-icon>
        <input
          v-model="recallQuery"
          class="brain-search__input"
          type="text"
          :placeholder="t('memory.brain.recallPlaceholder')"
        />
        <button v-if="recallQuery" type="button" class="brain-search__clear" @click="clearRecall">
          <el-icon :size="13"><Close /></el-icon>
        </button>
      </div>

      <!-- 检索演示：本地关键词打分，和线上向量召回无关 -->
      <template v-if="recallQuery">
        <p class="brain-note">{{ t('memory.brain.recallNote') }}</p>
        <ul class="brain-recall">
          <li
            v-for="row in recallResults"
            :key="row.memory.id"
            class="brain-recall__item"
            @click="selectMemoryById(row.memory.id)"
          >
            <p class="brain-recall__text">{{ row.memory.summary }}</p>
            <div class="brain-recall__meta">
              <span>{{ t('memory.brain.hits', { hits: row.hits, total: row.total }) }}</span>
              <span class="brain-recall__bar"><i :style="{ width: `${Math.round(row.score * 100)}%` }" /></span>
              <span class="brain-recall__score">{{ Math.round(row.score * 100) }}</span>
            </div>
          </li>
        </ul>
        <p v-if="!recallResults.length" class="brain-panel__empty">{{ t('memory.brain.recallEmpty') }}</p>
      </template>

      <!-- 脑区详情 -->
      <template v-else-if="activeRegion">
        <header class="brain-panel__head">
          <button type="button" class="brain-panel__back" @click="clearSelection">
            <el-icon :size="14"><ArrowLeft /></el-icon>
          </button>
          <span class="brain-panel__dot" :style="{ '--dot': activeRegion.color }" />
          <h3 class="brain-panel__name">{{ t(activeRegion.nameKey) }}</h3>
        </header>
        <p class="brain-panel__count">
          {{ activeRegion.count }} {{ t(activeRegion.unitKey) }}
        </p>
        <p class="brain-panel__desc">{{ t(activeRegion.descKey) }}</p>
        <ul class="brain-items">
          <li v-for="item in activeRegion.items" :key="item.id" class="brain-items__row" @click="pickItem(item)">
            <span class="brain-items__label">{{ itemLabel(item) }}</span>
            <span class="brain-items__hint">{{ itemHint(item) }}</span>
          </li>
        </ul>
        <p v-if="!activeRegion.items.length" class="brain-panel__empty">{{ t('memory.brain.regionEmpty') }}</p>
      </template>

      <!-- 概览 -->
      <template v-else>
        <div class="brain-stats">
          <div class="brain-stat">
            <b>{{ stats.total }}</b>
            <span>{{ t('memory.brain.statMemories') }}</span>
          </div>
          <div class="brain-stat">
            <b>{{ stats.weekAdded }}</b>
            <span>{{ t('memory.brain.statWeek') }}</span>
          </div>
          <div class="brain-stat">
            <b>{{ stats.characters }}</b>
            <span>{{ t('memory.brain.statCharacters') }}</span>
          </div>
          <div class="brain-stat">
            <b>{{ importancePercent }}</b>
            <span>{{ t('memory.brain.statImportance') }}</span>
          </div>
        </div>

        <div class="brain-sectors">
          <div
            v-for="sector in sectors"
            :key="sector.id"
            class="brain-sector"
            :style="{ '--dot': sector.color }"
          >
            <span class="brain-sector__name">{{ t(sector.nameKey) }}</span>
            <span class="brain-sector__bar"><i :style="{ width: `${sectorPercent(sector.id)}%` }" /></span>
            <span class="brain-sector__count">{{ stats.bySector[sector.id] }}</span>
          </div>
        </div>

        <h4 class="brain-panel__title">{{ t('memory.brain.recent') }}</h4>
        <ul class="brain-items">
          <li
            v-for="item in recentItems"
            :key="item.id"
            class="brain-items__row"
            @click="selectMemoryById(item.memoryId)"
          >
            <span class="brain-items__label">{{ item.label }}</span>
            <span class="brain-items__hint">{{ formatDate(item.at) }}</span>
          </li>
        </ul>
        <p v-if="!recentItems.length" class="brain-panel__empty">{{ t('memory.brain.regionEmpty') }}</p>

        <p v-if="stats.truncated" class="brain-note">
          {{ t('memory.brain.truncated', { count: stats.total }) }}
        </p>
      </template>
    </aside>

    <div v-if="timeline.length > 1" class="brain-scrub">
      <button
        type="button"
        class="brain-scrub__play"
        :disabled="!canReplay"
        :title="t('memory.brain.replay')"
        @click="toggleReplay"
      >
        <el-icon :size="13"><VideoPause v-if="replayPlaying" /><VideoPlay v-else /></el-icon>
      </button>
      <div class="brain-scrub__bars">
        <button
          v-for="(bucket, index) in timeline"
          :key="bucket.key"
          type="button"
          class="brain-scrub__bar"
          :class="{ 'is-lit': replayIndex < 0 || replayIndex >= index, 'is-current': replayIndex === index }"
          :style="{ '--h': `${bucketHeight(bucket)}%` }"
          :title="`${bucket.key} · ${bucket.count}`"
          @click="jumpReplay(index)"
        >
          <i />
        </button>
      </div>
      <span class="brain-scrub__label">{{ replayLabel }}</span>
    </div>
  </div>
</template>

<script setup>
import { computed, onActivated, onBeforeUnmount, onDeactivated, onMounted, ref, shallowRef, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { Aim, ArrowLeft, Close, Refresh, Search, VideoPause, VideoPlay } from '@element-plus/icons-vue'
import { formatSmartTime } from '@/utils/feedTime'
import {
  MEMORY_SECTORS,
  buildBrainGraph,
  buildTimeline,
  scoreRecall,
  toTimestamp
} from './memoryBrainModel'
import {
  SHELL_DOTS,
  SHELL_RINGS,
  applyDrag,
  applyWheel,
  clamp,
  createCamera,
  depthFade,
  fitBase,
  pickNearest,
  projectPoint
} from './brainProjection'

defineOptions({ name: 'MemoryBrainMap' })

const props = defineProps({
  memories: { type: Array, default: () => [] },
  characters: { type: Array, default: () => [] },
  states: { type: Array, default: () => [] },
  diaries: { type: Array, default: () => [] },
  selectedMemoryId: { type: [Number, String], default: null }
})

const emit = defineEmits(['select-memory'])

const { t, locale } = useI18n()

const FONT_STACK = "'PingFang SC', 'Microsoft YaHei', 'Hiragino Sans GB', system-ui, sans-serif"
const TAU = Math.PI * 2
/** 星图专用深空底色；DESIGN.md「brain-*」一节记录同一组值 */
const SPACE = { core: '#1b2740', mid: '#101827', edge: '#080c14' }
// 同一个渐变给 canvas 底和舞台 CSS 用，避免两处色值漂移
const stageStyle = { background: `radial-gradient(circle at 50% 42%, ${SPACE.core} 0%, ${SPACE.mid} 46%, ${SPACE.edge} 100%)` }
const MEMORY_SECTOR_NAME = new Map(MEMORY_SECTORS.map((s) => [s.id, s.nameKey]))

/* ---------------- 数据 ---------------- */

const graph = shallowRef(buildBrainGraph())
const timeline = ref([])

const regions = computed(() => graph.value.regions)
const stats = computed(() => graph.value.stats)
const sectors = computed(() => MEMORY_SECTORS)
const importancePercent = computed(() => `${Math.round(stats.value.avgImportance * 100)}%`)
const canReplay = computed(() => timeline.value.some((bucket) => bucket.count > 0))

const focusedRegion = ref('')
const selection = ref(null)
const recallQuery = ref('')
const recallResults = ref([])

const activeRegion = computed(() => {
  if (!selection.value || selection.value.kind !== 'region') return null
  return graph.value.regions.find((region) => region.id === selection.value.id) || null
})

const recentItems = computed(() =>
  graph.value.regions
    .find((region) => region.id === 'ltm')
    ?.items?.slice(0, 5)
    .map((item) => ({ ...item, at: toTimestamp(item.at) })) || []
)

function sectorPercent(sectorId) {
  const total = stats.value.total
  if (!total) return 0
  return Math.round(((stats.value.bySector[sectorId] || 0) / total) * 100)
}

function itemLabel(item) {
  if (!item) return ''
  if (item.labelKey) return t(item.labelKey)
  return item.label || ''
}

function itemHint(item) {
  if (!item) return ''
  if (item.kind === 'memory') {
    const sectorName = MEMORY_SECTOR_NAME.get(item.hint) ? t(MEMORY_SECTOR_NAME.get(item.hint)) : item.hint
    return sectorName || formatDate(item.at)
  }
  if (item.kind === 'sector') return `${item.hint}`
  return item.hint || ''
}

function formatDate(value) {
  const stamp = toTimestamp(value)
  if (!stamp) return ''
  return formatSmartTime(stamp, { t, locale: locale.value })
}

/* ---------------- 相机与渲染状态 ---------------- */

const rootRef = ref(null)
const stageRef = ref(null)
const canvasRef = ref(null)

const camera = createCamera()
const viewport = { width: 0, height: 0, base: 1 }
const stageSize = ref({ width: 0, height: 0 })
const compact = ref(false)
const reducedMotion = ref(false)
const autoRotate = ref(true)

let ctx = null
let rafId = 0
let running = false
let lastTime = 0
let visible = true
let resizeObserver = null
let dpr = 1
let backgroundCache = null

const sprites = new Map()
const pickables = []
const pulses = []
const sparks = []
const floaters = []
const flowSegments = new Map()
const memoryEdges = shallowRef([])
const latticeEdges = shallowRef([])
const highlight = ref(new Set())

let dragState = null
let pointerPos = { x: 0, y: 0 }
const hover = ref(null)
const tooltip = ref(null)

const replayIndex = ref(-1)
const replayPlaying = ref(false)
let replayAccumulator = 0

let introStart = -1
let introCursor = 0
const INTRO_STEPS = [
  { at: 120, region: 'input' },
  { at: 260, flow: 'input-hippo' },
  { at: 900, region: 'hippo' },
  { at: 1040, flow: 'hippo-ltm' },
  { at: 1680, region: 'ltm' },
  { at: 1820, flow: 'ltm-core' },
  { at: 2460, region: 'core' },
  { at: 2600, flow: 'core-output' },
  { at: 3240, region: 'output' }
]

/* ---------------- 构建 ---------------- */

function rebuildGraph({ intro = false } = {}) {
  const next = buildBrainGraph({
    memories: props.memories,
    characters: props.characters,
    states: props.states,
    diaries: props.diaries
  })
  const hadData = graph.value.stats.total > 0
  graph.value = next
  timeline.value = buildTimeline(props.memories, { days: 14 })
  frameGraph()
  replayIndex.value = -1
  replayPlaying.value = false

  flowSegments.clear()
  for (const flow of next.flows) {
    const from = next.regionMap.get(flow.from)
    const to = next.regionMap.get(flow.to)
    if (!from || !to) continue
    flowSegments.set(flow.id, { a: from.c, b: to.c, fromColor: from.color, toColor: to.color })
  }

  // 中枢连到每颗记忆的光丝，是「神经图谱」那种放射感的来源；相机变换时只做投影
  const hub = next.regionMap.get('core') || next.regionMap.get('ltm')
  memoryEdges.value = hub
    ? next.memoryNodes.map((node) => ({
        id: node.id,
        sector: node.sector,
        node,
        from: hub.c,
        to: node.pos
      }))
    : []

  // 同一扇区里相邻的光点连成短链，给「神经网」一点织理；跨扇区不连，免得糊成一片
  const sectorNodes = new Map()
  for (const node of next.memoryNodes) {
    if (!sectorNodes.has(node.sector)) sectorNodes.set(node.sector, [])
    sectorNodes.get(node.sector).push(node)
  }
  const lattice = []
  for (const [sectorId, list] of sectorNodes) {
    for (let i = 0; i < list.length - 1; i += 1) {
      lattice.push({ id: `${list[i].id}~${list[i + 1].id}`, sector: sectorId, from: list[i].pos, to: list[i + 1].pos })
    }
  }
  latticeEdges.value = lattice

  if (intro && !reducedMotion.value && next.stats.total > 0) {
    startIntro()
  } else if (hadData && next.stats.total > 0 && !reducedMotion.value) {
    addPulse('ltm', 1100, 0.7)
  }
  syncSelection()
}

function startIntro() {
  introStart = performance.now()
  introCursor = 0
}

function syncSelection() {
  const id = props.selectedMemoryId
  if (id == null) {
    if (selection.value?.kind === 'memory') selection.value = null
    return
  }
  selection.value = { kind: 'memory', id }
  const node = graph.value.memoryNodes.find((item) => String(item.memoryId) === String(id))
  if (node && !reducedMotion.value) addFloater(node, t('memory.brain.selected'))
}

/* ---------------- 尺寸 ---------------- */

function resize() {
  const stage = stageRef.value
  const canvas = canvasRef.value
  if (!stage || !canvas) return
  const rect = stage.getBoundingClientRect()
  if (!rect.width || !rect.height) return

  dpr = Math.min(window.devicePixelRatio || 1, 2)
  viewport.width = rect.width
  viewport.height = rect.height
  viewport.base = fitBase(rect.width, rect.height)
  stageSize.value = { width: rect.width, height: rect.height }

  canvas.width = Math.max(1, Math.round(rect.width * dpr))
  canvas.height = Math.max(1, Math.round(rect.height * dpr))
  canvas.style.width = `${rect.width}px`
  canvas.style.height = `${rect.height}px`

  ctx = canvas.getContext('2d')
  if (ctx) ctx.setTransform(dpr, 0, 0, dpr, 0, 0)
  backgroundCache = null

  // 用元素宽度而不是窗口宽度：侧栏展开/收起时窗口没变，画布却变窄了。
  // 阈值取「整块卡片」：卡片 < 980 时侧栏改到画布下方，避免两边都挤成一条缝。
  const rootWidth = rootRef.value?.getBoundingClientRect().width || rect.width
  compact.value = rootWidth < 980
  frameGraph()
}

/**
 * 把整张图装进画布：用当前 yaw/pitch 量一次所有光点的包围盒，再定 zoom 与 pan。
 * 固定基准会让四瓣在旋转后跑出画布，而「跑出去」恰恰是最难靠肉眼发现的错。
 */
function frameGraph() {
  if (!viewport.width || !viewport.height) return
  const regions = graph.value.regions
  if (!regions.length) return
  // 取景只围「真正有东西的地方」：脑区、光点、扇区锚点。脑壳是氛围层，
  // 把 ±214 的球面也算进去会让整团只占画布一半，宽屏上尤其明显。
  const points3d = [
    ...regions.map((region) => region.c),
    ...graph.value.memoryNodes.map((node) => node.pos),
    ...graph.value.anchors.map((anchor) => anchor.pos)
  ]

  camera.zoom = 1
  camera.panX = 0
  camera.panY = 0
  const projected = points3d.map((point) => projectPoint(point, camera, viewport))
  let minX = Infinity
  let minY = Infinity
  let maxX = -Infinity
  let maxY = -Infinity
  for (const point of projected) {
    if (!point.visible) continue
    minX = Math.min(minX, point.x)
    minY = Math.min(minY, point.y)
    maxX = Math.max(maxX, point.x)
    maxY = Math.max(maxY, point.y)
  }

  const spanX = Math.max(140, maxX - minX)
  const spanY = Math.max(140, maxY - minY)

  // 宽屏（全屏窗口）时星座横向摊开：一个 4:3 的团塞进 2:1 的画布，只按高度取景
  // 会白白浪费左右两侧，看起来就是「放大了还是小小一团」。spreadX 只拉宽屏幕分布，
  // 不改 z，前后遮挡关系保持不变。
  camera.spreadX = clamp(viewport.width / viewport.height / (spanX / spanY), 1, 1.9)
  const spreadPoints = points3d.map((point) => projectPoint(point, camera, viewport))
  let spreadMinX = Infinity
  let spreadMaxX = -Infinity
  for (const point of spreadPoints) {
    if (!point.visible) continue
    spreadMinX = Math.min(spreadMinX, point.x)
    spreadMaxX = Math.max(spreadMaxX, point.x)
  }
  const spreadSpanX = Math.max(140, spreadMaxX - spreadMinX)

  const fill = compact.value ? 0.82 : 0.8
  const nextZoom = Math.min(
    2.2,
    Math.max(0.5, Math.min((viewport.width * fill) / spreadSpanX, (viewport.height * fill) / spanY))
  )
  camera.zoom = nextZoom

  // 缩放过再量一次中心：只按未缩放的中心做 pan，放大后仍会偏
  const scaled = points3d.map((point) => projectPoint(point, camera, viewport))
  let scaledMinX = Infinity
  let scaledMinY = Infinity
  let scaledMaxX = -Infinity
  let scaledMaxY = -Infinity
  for (const point of scaled) {
    if (!point.visible) continue
    scaledMinX = Math.min(scaledMinX, point.x)
    scaledMinY = Math.min(scaledMinY, point.y)
    scaledMaxX = Math.max(scaledMaxX, point.x)
    scaledMaxY = Math.max(scaledMaxY, point.y)
  }
  camera.panX = viewport.width / 2 - (scaledMinX + scaledMaxX) / 2
  camera.panY = viewport.height / 2 - (scaledMinY + scaledMaxY) / 2
}

/* ---------------- 绘制原语 ---------------- */

function parseColor(hex) {
  const value = String(hex || '').replace('#', '')
  const full = value.length === 3 ? value.split('').map((c) => c + c).join('') : value
  const num = Number.parseInt(full, 16)
  if (!Number.isFinite(num)) return { r: 244, g: 166, b: 181 }
  return { r: (num >> 16) & 255, g: (num >> 8) & 255, b: num & 255 }
}

function rgba(hex, alpha) {
  const { r, g, b } = parseColor(hex)
  return `rgba(${r}, ${g}, ${b}, ${Math.max(0, Math.min(1, alpha))})`
}

function spriteFor(hex) {
  const cached = sprites.get(hex)
  if (cached) return cached
  const size = 96
  const canvas = document.createElement('canvas')
  canvas.width = size
  canvas.height = size
  const context = canvas.getContext('2d')
  const { r, g, b } = parseColor(hex)
  const gradient = context.createRadialGradient(size / 2, size / 2, 0, size / 2, size / 2, size / 2)
  gradient.addColorStop(0, `rgba(${r}, ${g}, ${b}, 1)`)
  gradient.addColorStop(0.13, `rgba(${r}, ${g}, ${b}, 0.92)`)
  gradient.addColorStop(0.3, `rgba(${r}, ${g}, ${b}, 0.34)`)
  gradient.addColorStop(0.62, `rgba(${r}, ${g}, ${b}, 0.07)`)
  gradient.addColorStop(1, `rgba(${r}, ${g}, ${b}, 0)`)
  context.fillStyle = gradient
  context.fillRect(0, 0, size, size)
  sprites.set(hex, canvas)
  return canvas
}

function glow(x, y, radius, hex, alpha) {
  if (!ctx || radius <= 0.15 || alpha <= 0.004) return
  const sprite = spriteFor(hex)
  const size = radius * 5.2
  // 用完立刻还原：后面的描边、文字都是 source-over 直接画，
  // 残留的 globalAlpha 会把它们一起调暗（标签发灰就是这个原因）
  const prevAlpha = ctx.globalAlpha
  ctx.globalAlpha = Math.min(1, alpha)
  ctx.drawImage(sprite, x - size / 2, y - size / 2, size, size)
  ctx.globalAlpha = prevAlpha
}

function mutedAlpha(alpha, muted) {
  return muted ? alpha * 0.35 : alpha
}

function drawBackground() {
  if (!ctx) return
  const { width, height } = viewport
  if (!backgroundCache || backgroundCache.width !== width || backgroundCache.height !== height) {
    const gradient = ctx.createRadialGradient(
      width * 0.5,
      height * 0.42,
      0,
      width * 0.5,
      height * 0.42,
      Math.max(width, height) * 0.78
    )
    gradient.addColorStop(0, SPACE.core)
    gradient.addColorStop(0.55, SPACE.mid)
    gradient.addColorStop(1, SPACE.edge)
    backgroundCache = gradient
  }
  ctx.globalCompositeOperation = 'source-over'
  ctx.globalAlpha = 1
  ctx.fillStyle = backgroundCache
  ctx.fillRect(0, 0, width, height)
}

/* ---------------- 效果 ---------------- */

function addPulse(regionId, duration = 900, strength = 1) {
  const region = graph.value.regionMap.get(regionId)
  if (!region) return
  pulses.push({ regionId, start: performance.now(), duration, color: region.color, strength })
  if (pulses.length > 24) pulses.shift()
}

function addSpark(flowId, duration = 1200, color) {
  const flow = graph.value.flows.find((item) => item.id === flowId)
  if (!flow) return
  const from = graph.value.regionMap.get(flow.from)
  sparks.push({
    flowId,
    start: performance.now(),
    duration,
    color: color || from?.color || '#ffffff'
  })
  if (sparks.length > 18) sparks.shift()
}

function addFloater(node, text) {
  const point = projectPoint(node.pos, camera, viewport)
  floaters.push({
    x: point.x,
    y: point.y - 12,
    text,
    color: node.color || '#ffffff',
    start: performance.now(),
    duration: 1600
  })
  if (floaters.length > 12) floaters.shift()
}

/* ---------------- 主循环 ---------------- */

function start() {
  if (running) return
  running = true
  lastTime = 0
  rafId = requestAnimationFrame(tick)
}

function stop() {
  running = false
  if (rafId) cancelAnimationFrame(rafId)
  rafId = 0
}

function tick(now) {
  if (!running) return
  rafId = requestAnimationFrame(tick)
  if (document.hidden || !visible || !ctx) return

  const delta = lastTime ? Math.min(64, now - lastTime) : 16
  lastTime = now

  // 悬停时停自转：卡片跟着光点走，转起来就永远对不准
  if (autoRotate.value && !dragState && !hover.value && !reducedMotion.value) {
    camera.yaw += delta * 0.000155
  }

  if (replayPlaying.value) {
    replayAccumulator += delta
    if (replayAccumulator > 520) {
      replayAccumulator = 0
      if (replayIndex.value < timeline.value.length - 1) {
        replayIndex.value += 1
      } else {
        replayPlaying.value = false
      }
    }
  }

  processIntro(now)
  render(now)
}

function processIntro(now) {
  if (introStart < 0) return
  const elapsed = now - introStart
  while (introCursor < INTRO_STEPS.length && elapsed >= INTRO_STEPS[introCursor].at) {
    const step = INTRO_STEPS[introCursor]
    if (step.region) addPulse(step.region, 1100, 0.9)
    if (step.flow) addSpark(step.flow, 1250)
    introCursor += 1
  }
  if (introCursor >= INTRO_STEPS.length && elapsed > 4600) introStart = -1
}

function render(now) {
  const { width, height } = viewport
  if (!width || !height) return

  drawBackground()

  pickables.length = 0
  const labels = []
  const fadeFor = (depth) => depthFade(depth)

  /* 脑壳：稀疏点阵 + 一条赤道线。壳只是「有个容器」的暗示，画满会变成星空噪点 */
  ctx.globalCompositeOperation = 'lighter'
  for (let i = 0; i < SHELL_DOTS.length; i += 2) {
    const point = projectPoint(SHELL_DOTS[i], camera, viewport)
    const alpha = (0.045 + 0.075 * fadeFor(point.depth)) * (point.depth > 0 ? 0.66 : 1)
    glow(point.x, point.y, 1.25, '#9fb8d8', alpha)
  }
  ctx.globalCompositeOperation = 'source-over'
  ctx.lineWidth = 1
  const equator = SHELL_RINGS[Math.floor(SHELL_RINGS.length / 2)]
  if (equator) {
    ctx.beginPath()
    for (let i = 0; i < equator.length; i += 1) {
      const point = projectPoint(equator[i], camera, viewport)
      if (i === 0) ctx.moveTo(point.x, point.y)
      else ctx.lineTo(point.x, point.y)
    }
    ctx.strokeStyle = 'rgba(159, 184, 216, 0.06)'
    ctx.stroke()
  }

  /* 数据流：默认只留一条主链（输入→写入→记忆→中枢→表达），
     其余连线只在该脑区被聚焦时出现，否则八条光束会盖过光点本身 */
  const MAIN_FLOWS = new Set(['input-hippo', 'hippo-ltm', 'ltm-core', 'core-output'])
  for (const flow of graph.value.flows) {
    const segment = flowSegments.get(flow.id)
    if (!segment) continue
    const a = projectPoint(segment.a, camera, viewport)
    const b = projectPoint(segment.b, camera, viewport)
    if (!a.visible || !b.visible) continue
    const touched = focusedRegion.value && (flow.from === focusedRegion.value || flow.to === focusedRegion.value)
    if (!MAIN_FLOWS.has(flow.id) && !touched) continue
    const dim = focusedRegion.value && !touched
    const alpha = dim ? 0.05 : touched ? 0.5 : 0.26

    drawBeam(a, b, segment.fromColor, segment.toColor, alpha, {
      dashed: flow.dash,
      arrow: !dim && (touched || focusedRegion.value === ''),
      arrowColor: segment.toColor,
      arrowAlpha: dim ? 0.08 : 0.5
    })

    // 聚焦某个脑区时，只给它相关的连线加文字说明，避免全场都是小字
    if (focusedRegion.value && !dim && !compact.value && touched) {
      const mid = { x: (a.x + b.x) / 2, y: (a.y + b.y) / 2 }
      ctx.globalCompositeOperation = 'source-over'
      drawLabelText(t(flow.labelKey), mid.x, mid.y - 8, {
        color: '#e8edf2',
        alpha: 0.72,
        size: 9.5
      })
      ctx.globalCompositeOperation = 'lighter'
    }
  }

  /* 扇区内的相邻短链：只在聚焦长期记忆库时画，平时这些细线只是噪点 */
  if (focusedRegion.value === 'ltm' && latticeEdges.value.length) {
    const groups = new Map()
    for (const edge of latticeEdges.value) {
      if (!groups.has(edge.sector)) groups.set(edge.sector, [])
      groups.get(edge.sector).push(edge)
    }
    for (const [sectorId, edges] of groups) {
      const meta = MEMORY_SECTORS.find((sector) => sector.id === sectorId)
      ctx.strokeStyle = rgba(meta?.color || '#9fb8d8', 0.08)
      ctx.lineWidth = 0.6
      ctx.beginPath()
      for (const edge of edges) {
        const a = projectPoint(edge.from, camera, viewport)
        const b = projectPoint(edge.to, camera, viewport)
        ctx.moveTo(a.x, a.y)
        ctx.lineTo(b.x, b.y)
      }
      ctx.stroke()
    }
  }

  /* 中枢 → 记忆的光丝：直线放射，扇区色跟着走，越靠近瓣心越密 */
  const fanVisible =
    focusedRegion.value === 'ltm' ||
    focusedRegion.value === 'core' ||
    hover.value?.kind === 'memory' ||
    Boolean(props.selectedMemoryId)
  if (memoryEdges.value.length && fanVisible) {
    const dimEdge = focusedRegion.value && focusedRegion.value !== 'ltm' && focusedRegion.value !== 'core'
    const edgeAlpha = dimEdge ? 0.02 : compact.value ? 0.05 : 0.08
    const hubPoint = projectPoint(memoryEdges.value[0].from, camera, viewport)
    const groups = new Map()
    for (const edge of memoryEdges.value) {
      if (!groups.has(edge.sector)) groups.set(edge.sector, [])
      groups.get(edge.sector).push(edge)
    }
    if (hubPoint.visible) {
      for (const [sectorId, edges] of groups) {
        const meta = MEMORY_SECTORS.find((sector) => sector.id === sectorId)
        ctx.strokeStyle = rgba(meta?.color || '#9fb8d8', edgeAlpha)
        ctx.lineWidth = 0.5
        ctx.beginPath()
        for (const edge of edges) {
          const target = projectPoint(edge.to, camera, viewport)
          if (!target.visible) continue
          ctx.moveTo(hubPoint.x, hubPoint.y)
          ctx.lineTo(target.x, target.y)
        }
        ctx.stroke()
      }
    }

    // 悬停/选中的那颗把光丝点亮，读起来像「中枢正在盯住这条记忆」
    const focusEdge =
      memoryEdges.value.find((edge) => String(edge.node.memoryId) === String(props.selectedMemoryId)) ||
      (hover.value?.kind === 'memory'
        ? memoryEdges.value.find((edge) => String(edge.node.memoryId) === String(hover.value.id))
        : null)
    if (focusEdge && hubPoint.visible) {
      const target = projectPoint(focusEdge.to, camera, viewport)
      if (target.visible) {
        ctx.strokeStyle = rgba(focusEdge.node.color, 0.62)
        ctx.lineWidth = 1.1
        ctx.beginPath()
        ctx.moveTo(hubPoint.x, hubPoint.y)
        ctx.lineTo(target.x, target.y)
        ctx.stroke()
      }
    }
  }

  for (const spark of sparks) {
    const segment = flowSegments.get(spark.flowId)
    if (!segment) continue
    const progress = (now - spark.start) / spark.duration
    if (progress < 0 || progress > 1) continue
    const eased = progress < 0.5 ? 2 * progress * progress : 1 - 2 * (1 - progress) * (1 - progress)
    const point = projectPoint(
      [
        segment.a[0] + (segment.b[0] - segment.a[0]) * eased,
        segment.a[1] + (segment.b[1] - segment.a[1]) * eased,
        segment.a[2] + (segment.b[2] - segment.a[2]) * eased
      ],
      camera,
      viewport
    )
    const alpha = Math.sin(Math.PI * progress) * 0.9
    glow(point.x, point.y, 5.4, spark.color, alpha * 0.5)
    glow(point.x, point.y, 1.4, '#ffffff', alpha * 0.9)
  }

  /* 扇区锚点：先记位置，等光点画完再落字，否则字会被自己的光团吃掉 */
  const sectorLabels = []
  for (const anchor of graph.value.anchors) {
    const point = projectPoint(anchor.pos, camera, viewport)
    if (!point.visible) continue
    const dimmed = focusedRegion.value && focusedRegion.value !== 'ltm'
    const alpha = dimmed ? 0.08 : anchor.count > 0 ? 0.34 : 0.14
    glow(point.x, point.y, 2.4, anchor.color, alpha)
    if (compact.value) continue
    sectorLabels.push({ anchor, point, dimmed })
  }

  /* 记忆光点：远的先画 */
  const cutoff = replayIndex.value >= 0 ? (timeline.value[replayIndex.value]?.at ?? 0) + 86400000 : Infinity
  const nodes = graph.value.memoryNodes
    .map((node) => ({ node, point: projectPoint(node.pos, camera, viewport) }))
    .sort((a, b) => b.point.depth - a.point.depth)

  for (const { node, point } of nodes) {
    if (!point.visible) continue
    const isSelected = String(node.memoryId) === String(props.selectedMemoryId)
    const isHovered = hover.value?.kind === 'memory' && hover.value.id === node.memoryId
    const isHighlighted = highlight.value.has(node.id)
    const dimmed =
      (focusedRegion.value && focusedRegion.value !== 'ltm') ||
      (node.at > cutoff) ||
      (highlight.value.size > 0 && !isHighlighted)

    const base = depthFade(point.depth)
    const strength = Number.isFinite(node.strength) ? node.strength : 1
    // 静音是「别再提了」，不是「没有过」：留在图上，但暗到不会先被看见。
    const alpha = mutedAlpha(
      dimmed ? base * 0.14 : base * (0.78 + node.importance * 0.22) * (0.55 + 0.45 * strength),
      node.muted
    )
    const radius = Math.max(1.6, node.size * point.scale * (node.muted ? 0.78 : 1))

    glow(point.x, point.y, radius * (isSelected || isHovered ? 3.2 : 1.75), node.color, alpha)
    // 芯用扇区色画实心、再点一颗小白点：颜色负责「是哪一类」，白点负责「这里有一颗」
    ctx.globalCompositeOperation = 'source-over'
    ctx.beginPath()
    ctx.arc(point.x, point.y, Math.max(0.9, radius * 0.74), 0, TAU)
    ctx.fillStyle = rgba(node.color, dimmed ? 0.22 : 0.55 + 0.4 * strength)
    ctx.fill()
    ctx.beginPath()
    ctx.arc(point.x, point.y, Math.max(0.42, radius * 0.3), 0, TAU)
    ctx.fillStyle = rgba('#ffffff', dimmed ? 0.28 : 0.88)
    ctx.fill()

    // 钉住的记忆加一圈暖金细环：它不衰减，图上也不该显得"快没了"。
    if (node.pinned) {
      ctx.beginPath()
      ctx.arc(point.x, point.y, radius + 2.6, 0, TAU)
      ctx.strokeStyle = rgba('#fff1c9', dimmed ? 0.18 : 0.5)
      ctx.lineWidth = 0.9
      ctx.stroke()
    }
    ctx.globalCompositeOperation = 'lighter'

    if (isSelected || isHovered || isHighlighted) {
      ctx.globalCompositeOperation = 'source-over'
      ctx.strokeStyle = rgba(node.color, isSelected ? 0.85 : 0.5)
      ctx.lineWidth = 1
      ctx.beginPath()
      ctx.arc(point.x, point.y, radius * 2.6 + Math.sin(now * 0.005) * 1.2, 0, TAU)
      ctx.stroke()
      ctx.globalCompositeOperation = 'lighter'
    }

    pickables.push({
      kind: 'memory',
      id: node.memoryId,
      node,
      x: point.x,
      y: point.y,
      hitRadius: Math.max(9, radius * 2.4),
      weight: 1.6
    })
  }

  /* 扇区名落在四瓣的中心：先占位，脑区标签才不会再压上来 */
  labelBoxes.length = 0
  if (sectorLabels.length) {
    const ltmRegion = graph.value.regionMap.get('ltm')
    const ltmPoint = ltmRegion ? projectPoint(ltmRegion.c, camera, viewport) : null
    for (const { anchor, point, dimmed } of sectorLabels) {
      const text = t(anchor.nameKey)
      let lx = point.x
      let ly = point.y
      if (ltmPoint) {
        lx = ltmPoint.x + (point.x - ltmPoint.x) * 0.44
        ly = ltmPoint.y + (point.y - ltmPoint.y) * 0.44
      }
      const box = textBox(text, lx, ly, { size: 10, weight: 600 })
      const padded = { x1: box.x1 - 7, y1: box.y1 - 4, x2: box.x2 + 7, y2: box.y2 + 4 }
      if (boxOverlaps(padded, 5)) continue
      drawLabelText(text, lx, ly, {
        color: anchor.color,
        alpha: dimmed ? 0.24 : 0.95,
        size: 10,
        weight: 600,
        plate: !dimmed
      })
      reserveBox(padded)
    }
  }

  /* 脑区 */
  const sortedRegions = graph.value.regions
    .map((region) => ({ region, point: projectPoint(region.c, camera, viewport) }))
    .sort((a, b) => b.point.depth - a.point.depth)

  for (const { region, point } of sortedRegions) {
    if (!point.visible) continue
    const dimmed = focusedRegion.value && focusedRegion.value !== region.id
    const base = depthFade(point.depth)
    const lit = region.lit && !dimmed
    const radius = Math.max(4, (region.r[0] + region.r[1]) * 0.5 * point.scale)
    // 脑区画成一枚实心光核，不再套大椭圆：大环看着像空轨道，会把四团记忆挤成配角
    const orb = Math.max(5.5, radius * 0.42)
    const breathe = reducedMotion.value || !region.lit ? 1 : 1 + Math.sin(now * 0.0016 + region.c[0]) * 0.03

    glow(point.x, point.y, orb * 1.9 * breathe, region.color, (lit ? 0.34 : 0.12) * base)
    glow(point.x, point.y, orb * 0.62, '#ffffff', (lit ? 0.34 : 0.12) * base)

    ctx.globalCompositeOperation = 'source-over'
    ctx.strokeStyle = rgba(region.color, (lit ? 0.42 : 0.14) * base)
    ctx.lineWidth = 1
    ctx.beginPath()
    ctx.ellipse(point.x, point.y, orb * 1.12, orb * 0.78, 0, 0, TAU)
    ctx.stroke()

    if (lit && focusHalo(region, point, now)) {
      ctx.beginPath()
      ctx.ellipse(point.x, point.y, orb * 1.5, orb * 1.05, 0, 0, TAU)
      ctx.strokeStyle = rgba(region.color, 0.16 * base)
      ctx.stroke()
    }

    // 中枢不再套虚线大轨道：环一多，画布上就分不清哪些是脑区、哪些是装饰

    ctx.globalCompositeOperation = 'lighter'
    pickables.push({
      kind: 'region',
      id: region.id,
      region,
      x: point.x,
      y: point.y,
      hitRadius: Math.max(14, orb * 1.2),
      weight: 1
    })

    labels.push({ region, point, radius: orb, dimmed })
  }

  /* 脑区标签最后画：后画才会压在记忆光点上面，否则中间那几团光会吃掉文字。
     默认只给「长期记忆库」留名字，其余脑区靠左下图例认；聚焦或悬停时再补一个，
     八个名字常驻是这张图最吵的一层。 */
  ctx.globalCompositeOperation = 'source-over'
  const hoveredRegionId = hover.value?.kind === 'region' ? hover.value.id : ''
  for (const { region, point, radius, dimmed } of labels.sort((a, b) => b.point.depth - a.point.depth)) {
    const alwaysShow = region.labelSide === 'center'
    if (!alwaysShow && focusedRegion.value !== region.id && hoveredRegionId !== region.id) continue
    const labelAlpha = dimmed ? 0.18 : region.lit ? 0.96 : 0.55
    const name = t(region.nameKey)
    const nameSize = compact.value ? 10 : 11
    const countSize = compact.value ? 8.5 : 9
    ctx.strokeStyle = rgba(region.color, labelAlpha * 0.26)
    ctx.lineWidth = 1

    // 长期记忆库是四瓣的整体，名字挂正中间，光点绕着它铺开
    if (region.labelSide === 'center') {
      const labelY = point.y - 8
      const nameBox = textBox(name, point.x, labelY, { size: nameSize })
      if (boxOverlaps(nameBox)) continue
      drawLabelText(name, point.x, labelY, {
        color: '#e8edf2',
        alpha: labelAlpha,
        size: nameSize,
        plate: true
      })
      reserveBox(nameBox)
      const countY = labelY + nameSize + 3
      const countBox = textBox(String(region.count), point.x, countY, { size: countSize })
      if (!boxOverlaps(countBox)) {
        drawLabelText(String(region.count), point.x, countY, {
          color: region.color,
          alpha: labelAlpha * 0.9,
          size: countSize
        })
        reserveBox(countBox)
      }
      continue
    }

    // 中枢压在长期记忆库的扇区中间，标签上下都会盖住光点，只能挂到右侧
    if (region.labelSide === 'right') {
      const labelX = point.x + radius * 0.86 + 10
      ctx.beginPath()
      ctx.moveTo(point.x + radius * 0.62, point.y)
      ctx.lineTo(labelX - 5, point.y)
      ctx.stroke()
      const nameBox = textBox(name, labelX, point.y, { size: nameSize, align: 'left' })
      if (boxOverlaps(nameBox)) continue
      const placed = drawLabelText(name, labelX, point.y, {
        color: '#e8edf2',
        alpha: labelAlpha,
        size: nameSize,
        align: 'left'
      })
      reserveBox(nameBox)
      const countBox = textBox(String(region.count), labelX + placed + 5, point.y, {
        size: countSize,
        align: 'left'
      })
      if (!boxOverlaps(countBox)) {
        drawLabelText(String(region.count), labelX + placed + 5, point.y, {
          color: region.color,
          alpha: labelAlpha * 0.85,
          size: countSize,
          align: 'left'
        })
        reserveBox(countBox)
      }
      continue
    }

    // 上下左右各试几个落点：宁可绕开，也不要少一个脑区名
    const gap = compact.value ? 9 : 12
    const baseOffset = radius * (compact.value ? 0.72 : 0.82) + gap
    const slots = []
    for (const dir of [-1, 1]) {
      for (const extra of [0, 12]) {
        slots.push({
          x: point.x,
          y: point.y + dir * (baseOffset + extra),
          align: 'center',
          baseline: dir === 1 ? 'top' : 'bottom'
        })
      }
      slots.push({
        x: point.x + (dir === -1 ? 1 : -1) * (radius * 0.9 + 6),
        y: point.y,
        align: dir === -1 ? 'left' : 'right',
        baseline: 'middle'
      })
    }

    let slot = null
    for (const candidate of slots) {
      const box = textBox(name, candidate.x, candidate.y, {
        size: nameSize,
        align: candidate.align,
        baseline: candidate.baseline
      })
      if (boxOverlaps(box)) continue
      slot = { ...candidate, box }
      break
    }
    if (!slot) continue

    // 引线：把文字和节点绑在一起，中间那几团光才不会认错标签
    ctx.beginPath()
    if (slot.baseline === 'middle') {
      const side = slot.align === 'left' ? 1 : -1
      ctx.moveTo(point.x + side * radius * 0.55, point.y)
      ctx.lineTo(slot.x - side * 5, slot.y)
    } else {
      ctx.moveTo(point.x, point.y + (slot.baseline === 'top' ? radius * 0.55 : -radius * 0.55))
      ctx.lineTo(slot.x, slot.y - (slot.baseline === 'top' ? 4 : -4))
    }
    ctx.stroke()

    const nameWidth = drawLabelText(name, slot.x, slot.y, {
      color: '#e8edf2',
      alpha: labelAlpha,
      size: nameSize,
      align: slot.align,
      baseline: slot.baseline
    })
    reserveBox(slot.box)

    const countX =
      slot.align === 'left'
        ? slot.x + nameWidth + 6
        : slot.align === 'right'
          ? slot.x - nameWidth - 6
          : slot.x + nameWidth / 2 + 6
    const countBox = textBox(String(region.count), countX, slot.y, {
      size: countSize,
      align: slot.align === 'right' ? 'right' : 'left',
      baseline: slot.baseline
    })
    if (!boxOverlaps(countBox)) {
      drawLabelText(String(region.count), countX, slot.y, {
        color: region.color,
        alpha: labelAlpha * 0.85,
        size: countSize,
        align: slot.align === 'right' ? 'right' : 'left',
        baseline: slot.baseline
      })
      reserveBox(countBox)
    }
  }
  ctx.globalCompositeOperation = 'lighter'

  /* 脉冲与飘字 */
  for (let i = pulses.length - 1; i >= 0; i -= 1) {
    const pulse = pulses[i]
    const region = graph.value.regionMap.get(pulse.regionId)
    if (!region) {
      pulses.splice(i, 1)
      continue
    }
    const progress = (now - pulse.start) / pulse.duration
    if (progress >= 1) {
      pulses.splice(i, 1)
      continue
    }
    const point = projectPoint(region.c, camera, viewport)
    const radius = (18 + progress * 96) * point.scale
    ctx.globalCompositeOperation = 'source-over'
    ctx.strokeStyle = rgba(pulse.color, (1 - progress) * 0.55 * pulse.strength)
    ctx.lineWidth = 1.4
    ctx.beginPath()
    ctx.ellipse(point.x, point.y, radius, radius * 0.68, 0, 0, TAU)
    ctx.stroke()
    ctx.globalCompositeOperation = 'lighter'
  }

  for (let i = floaters.length - 1; i >= 0; i -= 1) {
    const floater = floaters[i]
    const progress = (now - floater.start) / floater.duration
    if (progress >= 1) {
      floaters.splice(i, 1)
      continue
    }
    ctx.globalCompositeOperation = 'source-over'
    ctx.font = `500 11px ${FONT_STACK}`
    ctx.textAlign = 'center'
    ctx.textBaseline = 'middle'
    ctx.fillStyle = rgba(floater.color, (1 - progress) * 0.9)
    ctx.fillText(floater.text, floater.x, floater.y - progress * 26)
  }

  ctx.globalCompositeOperation = 'source-over'
  ctx.globalAlpha = 1
}

/* 画布文字的占位盒：标签按远近依次落，后落的重叠就换位或干脆让开 */
const labelBoxes = []

function textBox(text, x, y, options = {}) {
  if (!ctx) return null
  const { size = 10, weight = 500, align = 'center', baseline = 'middle' } = options
  ctx.font = `${weight} ${size}px ${FONT_STACK}`
  const width = ctx.measureText(text).width
  const height = size * 1.25
  const left = align === 'left' ? x : align === 'right' ? x - width : x - width / 2
  const top = baseline === 'top' ? y : baseline === 'bottom' ? y - height : y - height / 2
  return { x1: left, y1: top, x2: left + width, y2: top + height }
}

function boxOverlaps(box, pad = 3) {
  if (!box) return false
  return labelBoxes.some(
    (other) =>
      !(box.x2 + pad < other.x1 || box.x1 - pad > other.x2 || box.y2 + pad < other.y1 || box.y1 - pad > other.y2)
  )
}

function reserveBox(box) {
  if (box) labelBoxes.push(box)
}

function focusHalo(region, point, now) {
  if (focusedRegion.value === region.id) return true
  if (focusedRegion.value) return false
  // 有 7 天内新记忆的脑区轻轻呼吸，提示「这里刚写过东西」
  return region.id === 'ltm' && region.lit && Math.sin(now * 0.0012) > 0.7
}

/**
 * 脑区之间的数据流：三股平行直线拧成一根「神经束」，颜色从源脑区过渡到目标脑区，
 * 末端一个实心箭头。曲线更像装饰，直线才读得出「往哪儿送」。
 */
function drawBeam(a, b, fromColor, toColor, alpha, options = {}) {
  if (!ctx) return
  const { dashed = false, arrow = true, arrowColor = toColor, arrowAlpha = 0.6 } = options
  const dx = b.x - a.x
  const dy = b.y - a.y
  const len = Math.hypot(dx, dy)
  if (len < 14) return
  const nx = -dy / len
  const ny = dx / len

  // 用 source-over 画线：lighter 叠加会把两端颜色加白，看着就只剩一根白线
  ctx.globalCompositeOperation = 'source-over'
  ctx.setLineDash(dashed ? [4, 6] : [])

  ctx.strokeStyle = rgba(fromColor, alpha * 0.14)
  ctx.lineWidth = 3.6
  ctx.beginPath()
  ctx.moveTo(a.x, a.y)
  ctx.lineTo(b.x, b.y)
  ctx.stroke()

  const gradient = ctx.createLinearGradient(a.x, a.y, b.x, b.y)
  gradient.addColorStop(0, rgba(fromColor, alpha))
  gradient.addColorStop(0.62, rgba(fromColor, alpha * 0.88))
  gradient.addColorStop(1, rgba(toColor, alpha * 0.96))
  ctx.strokeStyle = gradient
  ctx.lineWidth = 1.15
  ctx.beginPath()
  ctx.moveTo(a.x, a.y)
  ctx.lineTo(b.x, b.y)
  ctx.stroke()
  ctx.globalCompositeOperation = 'lighter'

  // 两侧纤维更细更淡：有束感，但不会盖过记忆光点
  ctx.strokeStyle = rgba(fromColor, alpha * 0.3)
  ctx.lineWidth = 0.7
  for (const offset of [-3.4, 3.4]) {
    ctx.beginPath()
    ctx.moveTo(a.x + nx * offset, a.y + ny * offset)
    ctx.lineTo(b.x + nx * offset, b.y + ny * offset)
    ctx.stroke()
  }
  ctx.setLineDash([])

  if (!arrow) return
  const ux = dx / len
  const uy = dy / len
  const tipX = b.x - ux * 5
  const tipY = b.y - uy * 5
  const size = 5.4
  ctx.beginPath()
  ctx.moveTo(tipX, tipY)
  ctx.lineTo(tipX - ux * size + nx * size * 0.54, tipY - uy * size + ny * size * 0.54)
  ctx.lineTo(tipX - ux * size - nx * size * 0.54, tipY - uy * size - ny * size * 0.54)
  ctx.closePath()
  ctx.fillStyle = rgba(arrowColor, arrowAlpha)
  ctx.fill()
}

/**
 * 画布上的文字一律走这里：先用深色描边垫底、再填色。
 * shadowBlur 在 lighter 合成下会把字糊成一团，描边才是稳定的「底」。
 */
function drawLabelText(text, x, y, options = {}) {
  if (!ctx) return 0
  const {
    color = '#e8edf2',
    alpha = 0.95,
    size = 10,
    weight = 500,
    align = 'center',
    baseline = 'middle',
    plate = false
  } = options
  ctx.globalCompositeOperation = 'source-over'
  ctx.font = `${weight} ${size}px ${FONT_STACK}`
  ctx.textAlign = align
  ctx.textBaseline = baseline
  ctx.lineJoin = 'round'
  if (plate) {
    const width = ctx.measureText(text).width
    const padX = size * 0.52
    const padY = size * 0.34
    const left = align === 'left' ? x - padX : align === 'right' ? x - width - padX : x - width / 2 - padX
    const top = baseline === 'top' ? y - padY : baseline === 'bottom' ? y - size - padY : y - size * 0.62 - padY
    const height = size * 1.24 + padY * 2
    ctx.save()
    ctx.globalCompositeOperation = 'source-over'
    ctx.fillStyle = 'rgba(6, 10, 18, 0.62)'
    ctx.beginPath()
    if (typeof ctx.roundRect === 'function') {
      ctx.roundRect(left, top, width + padX * 2, height, Math.min(7, height / 2))
    } else {
      ctx.rect(left, top, width + padX * 2, height)
    }
    ctx.fill()
    ctx.restore()
  }

  ctx.lineWidth = Math.max(2.2, size * 0.32)
  ctx.strokeStyle = 'rgba(5, 8, 14, 0.88)'
  ctx.strokeText(text, x, y)
  ctx.fillStyle = rgba(color, alpha)
  ctx.fillText(text, x, y)
  return ctx.measureText(text).width
}

/* ---------------- 交互 ---------------- */

function onPointerDown(event) {
  if (event.pointerType === 'mouse' && event.button !== 0) return
  try {
    canvasRef.value?.setPointerCapture?.(event.pointerId)
  } catch {
    // 合成事件（测试/截图）没有真实指针，捕获会抛 NotFoundError；不影响后面的拾取
  }
  dragState = {
    pointerId: event.pointerId,
    lastX: event.clientX,
    lastY: event.clientY,
    moved: 0
  }
  hover.value = null
  tooltip.value = null
}

function onPointerMove(event) {
  const canvas = canvasRef.value
  if (!canvas) return
  const rect = canvas.getBoundingClientRect()
  pointerPos = { x: event.clientX - rect.left, y: event.clientY - rect.top }

  if (dragState && dragState.pointerId === event.pointerId) {
    const dx = event.clientX - dragState.lastX
    const dy = event.clientY - dragState.lastY
    dragState.lastX = event.clientX
    dragState.lastY = event.clientY
    dragState.moved += Math.abs(dx) + Math.abs(dy)
    const next = applyDrag(camera, dx, dy)
    camera.yaw = next.yaw
    camera.pitch = next.pitch
    return
  }

  const hit = pickNearest(pickables, pointerPos.x, pointerPos.y)
  if (!hit) {
    if (hover.value) {
      hover.value = null
      tooltip.value = null
    }
    return
  }
  if (hover.value?.kind === hit.kind && hover.value?.id === hit.id) return
  hover.value = hit
  tooltip.value = buildTooltip(hit)
}

function buildTooltip(hit) {
  if (hit.kind === 'region') {
    return {
      title: t(hit.region.nameKey),
      meta: `${hit.region.count} ${t(hit.region.unitKey)}`,
      hint: '',
      x: hit.x,
      y: hit.y
    }
  }
  const node = hit.node
  const sectorName = MEMORY_SECTOR_NAME.get(node.sector) ? t(MEMORY_SECTOR_NAME.get(node.sector)) : node.sector
  const percent = Math.round(Math.min(1, Math.max(0, Number(node.importance) || 0)) * 100)
  const meta = [sectorName, node.characterName, formatDate(node.at), `${percent}%`].filter(Boolean).join(' · ')
  return {
    title: node.title || '',
    meta,
    hint: node.pinned ? t('memory.brain.hoverPinned') : t('memory.brain.hoverHint'),
    x: hit.x,
    y: hit.y
  }
}

const tooltipStyle = computed(() => {
  const tip = tooltip.value
  if (!tip) return {}
  const { width, height } = stageSize.value
  const tipWidth = 264
  const tipHeight = 108
  const room = Math.max(12, width - tipWidth - 12)
  // 靠近右缘就翻到左侧，否则贴边时卡片会盖住半个画布
  const left = tip.x > width * 0.62 ? Math.max(12, tip.x - tipWidth - 14) : Math.min(tip.x + 14, room)
  const top = Math.min(Math.max(12, tip.y - tipHeight * 0.4), Math.max(12, height - tipHeight - 84))
  return { left: `${left}px`, top: `${top}px`, width: `${tipWidth}px` }
})

function onPointerUp(event) {
  if (!dragState || dragState.pointerId !== event.pointerId) return
  const moved = dragState.moved
  dragState = null
  try {
    canvasRef.value?.releasePointerCapture?.(event.pointerId)
  } catch {
    // 同上：没有真实指针时释放也会抛
  }
  if (moved > 6) return

  const canvas = canvasRef.value
  if (!canvas) return
  const rect = canvas.getBoundingClientRect()
  const hit = pickNearest(pickables, event.clientX - rect.left, event.clientY - rect.top)
  if (!hit) {
    clearSelection()
    return
  }
  if (hit.kind === 'region') {
    selection.value = { kind: 'region', id: hit.id }
    addPulse(hit.id, 900, 0.8)
    return
  }
  const node = hit.node
  selection.value = { kind: 'memory', id: node.memoryId }
  emit('select-memory', {
    id: node.memoryId,
    summary: node.title,
    characterId: node.characterId,
    characterName: node.characterName,
    importance: node.importance,
    createdAt: node.createdAt
  })
  addPulse('ltm', 700, 0.5)
}

function onPointerLeave() {
  if (dragState) return
  hover.value = null
  tooltip.value = null
}

function onWheel(event) {
  event.preventDefault()
  const next = applyWheel(camera, event.deltaY)
  camera.zoom = next.zoom
}

function onKeyDown(event) {
  const step = 0.08
  if (event.key === 'ArrowLeft') camera.yaw -= step
  else if (event.key === 'ArrowRight') camera.yaw += step
  else if (event.key === 'ArrowUp') camera.pitch = Math.max(-1.15, camera.pitch - step)
  else if (event.key === 'ArrowDown') camera.pitch = Math.min(1.15, camera.pitch + step)
  else if (event.key === '+' || event.key === '=') camera.zoom = Math.min(2.6, camera.zoom + 0.08)
  else if (event.key === '-') camera.zoom = Math.max(0.55, camera.zoom - 0.08)
  else if (event.key === 'Escape') clearSelection()
  else return
  event.preventDefault()
}

function toggleFocus(regionId) {
  // 点图例既高亮该脑区，也要把右侧面板切到它的详情，否则点了像没反应
  if (focusedRegion.value === regionId) {
    focusedRegion.value = ''
    if (selection.value?.kind === 'region' && selection.value.id === regionId) selection.value = null
    return
  }
  focusedRegion.value = regionId
  selection.value = { kind: 'region', id: regionId }
  if (!reducedMotion.value) addPulse(regionId, 900, 0.7)
}

function clearSelection() {
  selection.value = null
  focusedRegion.value = ''
}

function toggleAutoRotate() {
  autoRotate.value = !autoRotate.value
}

function resetView() {
  const next = createCamera()
  camera.yaw = next.yaw
  camera.pitch = next.pitch
  camera.zoom = 1
  camera.panX = 0
  camera.panY = 0
  camera.spreadX = 1
  frameGraph()
  focusedRegion.value = ''
  if (!reducedMotion.value) addPulse('ltm', 1000, 0.8)
}

function selectMemoryById(memoryId) {
  const node = graph.value.memoryNodes.find((item) => String(item.memoryId) === String(memoryId))
  if (!node) return
  selection.value = { kind: 'memory', id: memoryId }
  emit('select-memory', {
    id: node.memoryId,
    summary: node.title,
    characterId: node.characterId,
    characterName: node.characterName,
    importance: node.importance,
    createdAt: node.createdAt
  })
}

function pickItem(item) {
  if (!item) return
  if (item.kind === 'memory' && item.memoryId != null) selectMemoryById(item.memoryId)
}

/* ---------------- 回放 ---------------- */

function toggleReplay() {
  if (!canReplay.value) return
  replayPlaying.value = !replayPlaying.value
  if (replayPlaying.value) {
    replayAccumulator = 0
    if (replayIndex.value >= timeline.value.length - 1 || replayIndex.value < 0) {
      replayIndex.value = 0
    }
  }
}

function jumpReplay(index) {
  replayPlaying.value = false
  replayIndex.value = replayIndex.value === index ? -1 : index
}

function bucketHeight(bucket) {
  const max = Math.max(...timeline.value.map((item) => item.count), 1)
  return Math.max(14, Math.round((bucket.count / max) * 100))
}

const replayLabel = computed(() => {
  if (replayIndex.value < 0) return t('memory.brain.replayAll')
  const bucket = timeline.value[replayIndex.value]
  if (!bucket) return t('memory.brain.replayAll')
  return `${bucket.key.slice(5).replace('-', '/')} · ${bucket.count}`
})

/* ---------------- 召回 ---------------- */

watch(recallQuery, (query) => {
  const trimmed = String(query || '').trim()
  if (!trimmed) {
    recallResults.value = []
    highlight.value = new Set()
    return
  }
  const rows = scoreRecall(props.memories, trimmed, { limit: 8 })
  recallResults.value = rows
  highlight.value = new Set(rows.map((row) => `mem-${row.memory.id}`))
})

function clearRecall() {
  recallQuery.value = ''
}

/* ---------------- 生命周期 ---------------- */

watch(
  () => [props.memories, props.characters, props.states, props.diaries],
  () => rebuildGraph(),
  { deep: false }
)

watch(
  () => props.selectedMemoryId,
  () => syncSelection()
)

function onVisibilityChange() {
  if (document.hidden) {
    lastTime = 0
  }
}

onMounted(() => {
  reducedMotion.value =
    typeof window.matchMedia === 'function' &&
    window.matchMedia('(prefers-reduced-motion: reduce)').matches
  autoRotate.value = !reducedMotion.value

  resize()
  const canvas = canvasRef.value
  canvas?.addEventListener('wheel', onWheel, { passive: false })

  if (typeof ResizeObserver === 'function' && stageRef.value) {
    resizeObserver = new ResizeObserver((entries) => {
      visible = entries.some((entry) => entry.isIntersecting !== false && entry.contentRect.height > 0)
      resize()
    })
    resizeObserver.observe(stageRef.value)
  } else {
    window.addEventListener('resize', resize)
  }
  window.addEventListener('resize', resize)
  document.addEventListener('visibilitychange', onVisibilityChange)

  rebuildGraph({ intro: true })
  start()
})

onActivated(() => {
  visible = true
  lastTime = 0
  start()
})

onDeactivated(() => {
  stop()
})

onBeforeUnmount(() => {
  stop()
  resizeObserver?.disconnect()
  resizeObserver = null
  window.removeEventListener('resize', resize)
  document.removeEventListener('visibilitychange', onVisibilityChange)
  canvasRef.value?.removeEventListener('wheel', onWheel)
  sprites.clear()
  pickables.length = 0
  pulses.length = 0
  sparks.length = 0
  floaters.length = 0
  flowSegments.clear()
})
</script>

<style lang="scss" scoped>
.brain-map {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 296px;
  grid-template-rows: minmax(0, 1fr) auto;
  border-radius: $radius-lg;
  overflow: hidden;
  background: $color-bg-deepest;
  border: 1px solid rgba($color-pink-rgb, 0.08);
  box-shadow: 0 18px 56px rgba(0, 0, 0, 0.42);
}

.brain-map__stage {
  position: relative;
  grid-column: 1;
  grid-row: 1;
  // 跟随窗口高度生长：全屏时星图必须跟着变大，固定 620px 会让内容缩在中间
  height: clamp(540px, calc(100vh - 250px), 1180px);
  overflow: hidden;
  background: $color-bg-deepest;
}

.brain-map__canvas {
  display: block;
  width: 100%;
  height: 100%;
  cursor: grab;
  outline: none;

  &:active {
    cursor: grabbing;
  }

  &:focus-visible {
    box-shadow: inset 0 0 0 1px rgba($color-pink-rgb, 0.35);
  }
}

/* 图例 */
.brain-legend {
  position: absolute;
  top: $space-4;
  left: $space-4;
  display: flex;
  flex-direction: column;
  gap: 2px;
  margin: 0;
  padding: 0;
  list-style: none;
  max-width: 46%;
}

.brain-legend__item {
  display: flex;
  align-items: center;
  gap: $space-2;
  padding: 3px 6px;
  border-radius: $radius-sm;
  cursor: pointer;
  transition: background $transition-fast, opacity $transition-base;

  &:hover {
    background: rgba(255, 255, 255, 0.05);
  }

  &.is-active {
    background: rgba(255, 255, 255, 0.08);
  }

  &.is-dim {
    opacity: 0.42;
  }
}

.brain-legend__dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--dot, #{$color-pink-primary});
  box-shadow: 0 0 8px var(--dot, #{$color-pink-primary});
  flex-shrink: 0;
}

.brain-legend__label {
  font-size: 11px;
  color: rgba($color-text-secondary, 0.86);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.brain-legend__count {
  margin-left: auto;
  padding-left: $space-2;
  font-size: 10px;
  font-variant-numeric: tabular-nums;
  color: rgba($color-text-muted, 0.8);
}

/* 工具条 */
.brain-tools {
  position: absolute;
  right: $space-4;
  top: $space-4;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.brain-tool {
  width: 30px;
  height: 30px;
  display: grid;
  place-items: center;
  border-radius: 50%;
  border: 1px solid rgba(255, 255, 255, 0.09);
  background: rgba(10, 16, 26, 0.55);
  color: rgba($color-text-secondary, 0.9);
  cursor: pointer;
  transition: color $transition-fast, border-color $transition-fast, background $transition-fast;

  &:hover:not(:disabled) {
    color: $color-pink-light;
    border-color: rgba($color-pink-rgb, 0.4);
  }

  &.is-on {
    color: $color-pink-primary;
    border-color: rgba($color-pink-rgb, 0.5);
    background: rgba($color-pink-rgb, 0.1);
  }

  &:disabled {
    opacity: 0.35;
    cursor: default;
  }
}

.brain-hint {
  position: absolute;
  left: $space-4;
  bottom: $space-3;
  margin: 0;
  font-size: 10px;
  letter-spacing: 0.02em;
  color: rgba($color-text-muted, 0.62);
  pointer-events: none;
}

.brain-tip {
  position: absolute;
  z-index: 3;
  max-width: 264px;
  padding: 8px 11px;
  border-radius: $radius-sm;
  background: rgba(8, 12, 20, 0.9);
  border: 1px solid rgba($color-pink-rgb, 0.18);
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.42);
  backdrop-filter: blur(8px);
  pointer-events: none;
  display: flex;
  flex-direction: column;
  gap: 3px;
  will-change: left, top;
}

.brain-tip__title {
  font-size: 11.5px;
  line-height: 1.5;
  color: $color-text-primary;
  display: -webkit-box;
  -webkit-line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.brain-tip__meta {
  font-size: 10px;
  color: rgba($color-text-secondary, 0.92);
}

.brain-tip__hint {
  font-size: 9.5px;
  color: rgba($color-pink-rgb, 0.85);
}

.brain-empty {
  position: absolute;
  left: 50%;
  bottom: 18%;
  transform: translateX(-50%);
  margin: 0;
  font-size: $font-size-sm;
  color: rgba($color-text-muted, 0.85);
  text-align: center;
  pointer-events: none;
}

/* 侧栏 */
.brain-panel {
  grid-column: 2;
  grid-row: 1;
  display: flex;
  flex-direction: column;
  gap: $space-3;
  padding: $space-4;
  overflow-y: auto;
  background: rgba(12, 18, 28, 0.72);
  border-left: 1px solid rgba($color-pink-rgb, 0.08);
  backdrop-filter: blur(14px);

  &::-webkit-scrollbar {
    width: 4px;
  }

  &::-webkit-scrollbar-thumb {
    background: rgba($color-pink-rgb, 0.18);
    border-radius: 2px;
  }
}

.brain-search {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 6px 10px;
  border-radius: $radius-pill;
  border: 1px solid rgba($color-pink-rgb, 0.14);
  background: rgba(255, 255, 255, 0.04);
  color: rgba($color-text-muted, 0.9);
}

.brain-search__input {
  flex: 1;
  min-width: 0;
  border: none;
  outline: none;
  background: transparent;
  color: $color-text-primary;
  font-size: 12px;
  font-family: inherit;

  &::placeholder {
    color: rgba($color-text-muted, 0.62);
  }
}

.brain-search__clear {
  display: grid;
  place-items: center;
  width: 18px;
  height: 18px;
  border: none;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.08);
  color: $color-text-secondary;
  cursor: pointer;
}

.brain-note {
  margin: 0;
  font-size: 10px;
  line-height: 1.5;
  color: rgba($color-text-muted, 0.78);
}

.brain-stats {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px;
}

.brain-stat {
  padding: 10px;
  border-radius: $radius-md;
  background: rgba(255, 255, 255, 0.035);
  border: 1px solid rgba(255, 255, 255, 0.05);

  b {
    display: block;
    font-size: $font-size-lg;
    font-weight: $font-weight-semibold;
    color: $color-text-primary;
    font-variant-numeric: tabular-nums;
  }

  span {
    font-size: 10px;
    color: rgba($color-text-muted, 0.9);
  }
}

.brain-sectors {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.brain-sector {
  display: grid;
  grid-template-columns: 46px 1fr 26px;
  align-items: center;
  gap: 8px;
}

.brain-sector__name {
  font-size: 10px;
  color: rgba($color-text-secondary, 0.86);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.brain-sector__bar {
  height: 4px;
  border-radius: 2px;
  background: rgba(255, 255, 255, 0.06);
  overflow: hidden;

  i {
    display: block;
    height: 100%;
    border-radius: 2px;
    background: var(--dot, #{$color-pink-primary});
    box-shadow: 0 0 8px var(--dot, #{$color-pink-primary});
    transition: width $transition-slow;
  }
}

.brain-sector__count {
  font-size: 10px;
  text-align: right;
  color: rgba($color-text-muted, 0.9);
  font-variant-numeric: tabular-nums;
}

.brain-panel__head {
  display: flex;
  align-items: center;
  gap: 6px;
}

.brain-panel__back {
  display: grid;
  place-items: center;
  width: 22px;
  height: 22px;
  border-radius: 50%;
  border: 1px solid rgba(255, 255, 255, 0.08);
  background: transparent;
  color: $color-text-secondary;
  cursor: pointer;

  &:hover {
    color: $color-pink-light;
  }
}

.brain-panel__dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--dot, #{$color-pink-primary});
  box-shadow: 0 0 10px var(--dot, #{$color-pink-primary});
}

.brain-panel__name {
  margin: 0;
  font-size: $font-size-sm;
  font-weight: $font-weight-semibold;
  color: $color-text-primary;
}

.brain-panel__count {
  margin: 0;
  font-size: 10px;
  color: rgba($color-text-muted, 0.86);
}

.brain-panel__desc {
  margin: 0;
  font-size: 11px;
  line-height: 1.7;
  color: rgba($color-text-secondary, 0.9);
}

.brain-panel__title {
  margin: $space-2 0 0;
  font-size: 10px;
  font-weight: $font-weight-semibold;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  color: rgba($color-text-muted, 0.9);
}

.brain-panel__empty {
  margin: 0;
  padding: $space-3 0;
  font-size: 11px;
  color: rgba($color-text-muted, 0.7);
}

.brain-items {
  display: flex;
  flex-direction: column;
  gap: 2px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.brain-items__row {
  display: flex;
  align-items: baseline;
  gap: $space-2;
  padding: 6px 8px;
  border-radius: $radius-sm;
  cursor: pointer;
  transition: background $transition-fast;

  &:hover {
    background: rgba($color-pink-rgb, 0.07);
  }
}

.brain-items__label {
  flex: 1;
  min-width: 0;
  font-size: 11px;
  line-height: 1.5;
  color: rgba($color-text-secondary, 0.95);
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.brain-items__hint {
  flex-shrink: 0;
  font-size: 10px;
  color: rgba($color-text-muted, 0.7);
}

.brain-recall {
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.brain-recall__item {
  padding: 7px 8px;
  border-radius: $radius-sm;
  border: 1px solid rgba(255, 255, 255, 0.05);
  background: rgba(255, 255, 255, 0.03);
  cursor: pointer;

  &:hover {
    border-color: rgba($color-pink-rgb, 0.3);
  }
}

.brain-recall__text {
  margin: 0 0 4px;
  font-size: 11px;
  line-height: 1.5;
  color: rgba($color-text-secondary, 0.95);
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.brain-recall__meta {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 9px;
  color: rgba($color-text-muted, 0.85);
}

.brain-recall__bar {
  flex: 1;
  height: 3px;
  border-radius: 2px;
  background: rgba(255, 255, 255, 0.07);
  overflow: hidden;

  i {
    display: block;
    height: 100%;
    background: linear-gradient(90deg, rgba($color-pink-rgb, 0.5), #{$color-pink-primary});
  }
}

.brain-recall__score {
  font-variant-numeric: tabular-nums;
}

/* 时间轴 */
.brain-scrub {
  grid-column: 1 / -1;
  grid-row: 2;
  display: flex;
  align-items: center;
  gap: $space-3;
  padding: 8px $space-4;
  background: rgba(8, 12, 20, 0.92);
  border-top: 1px solid rgba(255, 255, 255, 0.05);
}

.brain-scrub__play {
  display: grid;
  place-items: center;
  width: 24px;
  height: 24px;
  border-radius: 50%;
  border: 1px solid rgba($color-pink-rgb, 0.24);
  background: rgba($color-pink-rgb, 0.08);
  color: $color-pink-light;
  cursor: pointer;

  &:disabled {
    opacity: 0.3;
    cursor: default;
  }
}

.brain-scrub__bars {
  flex: 1;
  display: flex;
  align-items: flex-end;
  gap: 2px;
  height: 22px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.06);
}

.brain-scrub__bar {
  flex: 1;
  height: 100%;
  display: flex;
  align-items: flex-end;
  padding: 0;
  border: none;
  background: transparent;
  cursor: pointer;

  i {
    display: block;
    width: 34%;
    min-height: 3px;
    margin-inline: auto;
    height: var(--h, 20%);
    border-radius: 2px;
    background: rgba(255, 255, 255, 0.07);
    transition: background $transition-fast, height $transition-base;
  }

  &.is-lit i {
    background: linear-gradient(180deg, rgba($color-pink-rgb, 0.8), rgba($color-pink-rgb, 0.24));
  }

  &.is-current i {
    background: $color-pink-light;
    box-shadow: 0 0 10px rgba($color-pink-rgb, 0.7);
  }

  &:hover i {
    background: rgba($color-pink-rgb, 0.7);
  }
}

.brain-scrub__label {
  min-width: 78px;
  text-align: right;
  font-size: 10px;
  color: rgba($color-text-muted, 0.85);
  font-variant-numeric: tabular-nums;
}

/* 窄屏：画布在上，侧栏在下，时间轴压到最后。
   阈值由元素宽度（resize() 里的 compact）决定，不再看窗口宽度——
   侧栏展开时窗口没变、内容区却变窄了，媒体查询会误判。 */
.brain-map--compact {
  grid-template-columns: minmax(0, 1fr);
  grid-template-rows: auto auto auto;

  // 窄屏把图例排成两列：8 行竖排会把画布左上角整块吃掉
  .brain-legend {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 0 6px;
    max-width: 240px;
    padding: 4px 6px;
    border-radius: $radius-sm;
    background: rgba(8, 12, 20, 0.5);
    backdrop-filter: blur(6px);
  }

  .brain-legend__item {
    padding: 1px 4px;
  }

  .brain-panel {
    grid-column: 1;
    grid-row: 2;
    border-left: none;
    border-top: 1px solid rgba($color-pink-rgb, 0.08);
    max-height: 300px;
  }

  .brain-scrub {
    grid-row: 3;
  }
}

@media (prefers-reduced-motion: reduce) {
  .brain-map * {
    transition-duration: 1ms !important;
  }
}
</style>
