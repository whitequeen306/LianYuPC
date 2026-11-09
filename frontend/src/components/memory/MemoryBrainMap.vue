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
  hashString,
  mulberry32,
  scoreRecall,
  toTimestamp
} from './memoryBrainModel'
import {
  SHELL_DOTS,
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
const sectorSpines = shallowRef([])
const clusterSpines = shallowRef([])
// 类型枢纽之间的编织线：只做结构感，不承担语义
const meshEdges = shallowRef([])
const memoryEdges = shallowRef([])
const relatedEdges = shallowRef([])
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

  // 中枢只连出现的记忆类型枢纽；类型枢纽再分到扇区簇，成员线留在各自簇内。
  const hub = next.regionMap.get('core') || next.regionMap.get('ltm')
  sectorSpines.value = hub
    ? next.sectorHubs.map((sectorHub) => ({ id: sectorHub.id, sectorHub, from: hub.c, to: sectorHub.pos }))
    : []
  const sectorHubsById = new Map(next.sectorHubs.map((sectorHub) => [sectorHub.id, sectorHub]))
  clusterSpines.value = next.clusters.flatMap((cluster) => {
    if (cluster.central) return []
    const parent = sectorHubsById.get(cluster.sectorHubId)
    return parent ? [{ id: cluster.id, cluster, sectorHub: parent, from: parent.pos, to: cluster.pos }] : []
  })
  const nodesById = new Map(next.memoryNodes.map((node) => [node.id, node]))
  // 编织层：类型枢纽之间互相连一层极淡的网，中段才不会空成一片黑。
  // 这不是编出来的记忆关联（真实关联只走 relatedEdges），是「同属一个长期记忆库」的结构线。
  const hubsForMesh = [...next.sectorHubs, ...next.clusters.map((cluster) => ({ id: cluster.id, pos: cluster.pos, color: cluster.color }))]
  meshEdges.value = []
  for (let i = 0; i < hubsForMesh.length; i += 1) {
    for (let j = i + 1; j < hubsForMesh.length; j += 1) {
      const a = hubsForMesh[i]
      const b = hubsForMesh[j]
      const mid = [(a.pos[0] + b.pos[0]) / 2, (a.pos[1] + b.pos[1]) / 2, (a.pos[2] + b.pos[2]) / 2]
      const center = next.regionMap.get('ltm')?.c || [0, 0, 0]
      // 控制点向长期记忆库收一点：所有线都穿同一点会糊成一个结
      const control = [
        mid[0] * 0.62 + center[0] * 0.38,
        mid[1] * 0.62 + center[1] * 0.38 - 26,
        mid[2] * 0.62 + center[2] * 0.38
      ]
      meshEdges.value.push({ id: `${a.id}-${b.id}`, a: a.pos, b: b.pos, control, colorA: a.color, colorB: b.color })
    }
  }
  memoryEdges.value = next.clusters.flatMap((cluster) => {
    const members = cluster.memberIds.map((id) => nodesById.get(id)).filter(Boolean)
    const edgeLimit = Math.min(members.length, Math.max(4, Math.ceil(Math.sqrt(members.length) * 2.2)))
    const representatives = new Set(
      [...members]
        .sort((a, b) => b.importance - a.importance || b.at - a.at)
        .slice(0, edgeLimit)
        .map((node) => node.id)
    )
    return members.map((node) => ({
      id: node.id,
      clusterId: cluster.id,
      cluster,
      sector: node.sector,
      node,
      representative: representatives.has(node.id),
      from: cluster.pos,
      to: node.pos
    }))
  })
  relatedEdges.value = next.relatedEdges

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
  gradient.addColorStop(0.08, `rgba(${r}, ${g}, ${b}, 0.95)`)
  gradient.addColorStop(0.2, `rgba(${r}, ${g}, ${b}, 0.52)`)
  gradient.addColorStop(0.45, `rgba(${r}, ${g}, ${b}, 0.16)`)
  gradient.addColorStop(0.72, `rgba(${r}, ${g}, ${b}, 0.04)`)
  gradient.addColorStop(1, `rgba(${r}, ${g}, ${b}, 0)`)
  context.fillStyle = gradient
  context.fillRect(0, 0, size, size)
  sprites.set(hex, canvas)
  return canvas
}

function glow(x, y, radius, hex, alpha) {
  if (!ctx || radius <= 0.15 || alpha <= 0.004) return
  const sprite = spriteFor(hex)
  const size = radius * 9.6
  // 用完立刻还原：后面的描边、文字都是 source-over 直接画，
  // 残留的 globalAlpha 会把它们一起调暗（标签发灰就是这个原因）
  const prevAlpha = ctx.globalAlpha
  ctx.globalAlpha = Math.min(1, alpha)
  ctx.drawImage(sprite, x - size / 2, y - size / 2, size, size)
  ctx.globalAlpha = prevAlpha
}

/**
 * 多层光晕：例图的关键节点光晕是 80-150px 的多层渐变。
 * 核心白点 → 内层亮黄 → 中层透明金 → 外层环境光。
 */
function glowMulti(x, y, radius, hex, alpha) {
  if (!ctx || radius <= 0.15 || alpha <= 0.004) return
  const { r, g, b } = parseColor(hex)
  // 外层环境光
  const outerSize = radius * 12
  const outerGradient = ctx.createRadialGradient(x, y, 0, x, y, outerSize / 2)
  outerGradient.addColorStop(0, `rgba(${r}, ${g}, ${b}, ${alpha * 0.12})`)
  outerGradient.addColorStop(0.3, `rgba(${r}, ${g}, ${b}, ${alpha * 0.05})`)
  outerGradient.addColorStop(1, `rgba(${r}, ${g}, ${b}, 0)`)
  ctx.fillStyle = outerGradient
  ctx.fillRect(x - outerSize / 2, y - outerSize / 2, outerSize, outerSize)

  // 中层光晕
  const midSize = radius * 6
  const midGradient = ctx.createRadialGradient(x, y, 0, x, y, midSize / 2)
  midGradient.addColorStop(0, `rgba(${r}, ${g}, ${b}, ${alpha * 0.4})`)
  midGradient.addColorStop(0.4, `rgba(${r}, ${g}, ${b}, ${alpha * 0.15})`)
  midGradient.addColorStop(1, `rgba(${r}, ${g}, ${b}, 0)`)
  ctx.fillStyle = midGradient
  ctx.fillRect(x - midSize / 2, y - midSize / 2, midSize, midSize)

  // 内层亮核
  const innerSize = radius * 3
  const innerGradient = ctx.createRadialGradient(x, y, 0, x, y, innerSize / 2)
  innerGradient.addColorStop(0, `rgba(255, 255, 255, ${alpha * 0.9})`)
  innerGradient.addColorStop(0.3, `rgba(${r}, ${g}, ${b}, ${alpha * 0.7})`)
  innerGradient.addColorStop(1, `rgba(${r}, ${g}, ${b}, 0)`)
  ctx.fillStyle = innerGradient
  ctx.fillRect(x - innerSize / 2, y - innerSize / 2, innerSize, innerSize)
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
      height * 0.38,
      0,
      width * 0.5,
      height * 0.38,
      Math.max(width, height) * 0.82
    )
    gradient.addColorStop(0, SPACE.core)
    gradient.addColorStop(0.42, SPACE.mid)
    gradient.addColorStop(1, SPACE.edge)
    backgroundCache = gradient
  }
  ctx.globalCompositeOperation = 'source-over'
  ctx.globalAlpha = 1
  ctx.fillStyle = backgroundCache
  ctx.fillRect(0, 0, width, height)

  // 星云感：例图背景有淡淡的紫色/蓝色星云渐变，不是纯黑
  const nebula1 = ctx.createRadialGradient(
    width * 0.3,
    height * 0.25,
    0,
    width * 0.3,
    height * 0.25,
    width * 0.4
  )
  nebula1.addColorStop(0, 'rgba(169, 139, 255, 0.062)')
  nebula1.addColorStop(0.5, 'rgba(169, 139, 255, 0.026)')
  nebula1.addColorStop(1, 'rgba(169, 139, 255, 0)')
  ctx.fillStyle = nebula1
  ctx.fillRect(0, 0, width, height)

  const nebula2 = ctx.createRadialGradient(
    width * 0.75,
    height * 0.7,
    0,
    width * 0.75,
    height * 0.7,
    width * 0.35
  )
  nebula2.addColorStop(0, 'rgba(111, 219, 192, 0.042)')
  nebula2.addColorStop(0.5, 'rgba(111, 219, 192, 0.016)')
  nebula2.addColorStop(1, 'rgba(111, 219, 192, 0)')
  ctx.fillStyle = nebula2
  ctx.fillRect(0, 0, width, height)

  // 中轴赤道附近再垫一层暖光，让「中枢在上、记忆库居中」的纵向层次有落点
  const nebula3 = ctx.createRadialGradient(
    width * 0.5,
    height * 0.52,
    0,
    width * 0.5,
    height * 0.52,
    width * 0.3
  )
  nebula3.addColorStop(0, 'rgba(255, 214, 160, 0.035)')
  nebula3.addColorStop(0.6, 'rgba(255, 214, 160, 0.012)')
  nebula3.addColorStop(1, 'rgba(255, 214, 160, 0)')
  ctx.fillStyle = nebula3
  ctx.fillRect(0, 0, width, height)

  // 底部扇形微光：参考例图里长期记忆库下方有一圈向外散开的点阵
  const fanY = height * 0.86
  const fanGradient = ctx.createRadialGradient(width * 0.5, fanY, 0, width * 0.5, fanY, width * 0.34)
  fanGradient.addColorStop(0, 'rgba(244, 166, 181, 0.08)')
  fanGradient.addColorStop(0.5, 'rgba(244, 166, 181, 0.03)')
  fanGradient.addColorStop(1, 'rgba(244, 166, 181, 0)')
  ctx.fillStyle = fanGradient
  ctx.fillRect(0, fanY - height * 0.2, width, height * 0.2)
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
    if (step.region) addPulse(step.region, 850, 0.55)
    if (step.flow && focusedRegion.value) addSpark(step.flow, 900)
    introCursor += 1
  }
  if (introCursor >= INTRO_STEPS.length && elapsed > 4600) introStart = -1
}

/* 星空底噪：一张与数据无关的静态星场。
   例图那种「星域感」主要来自背景里成千上万个微小光点，而不是记忆本身；
   所以这部分必须固定存在，记忆只有十几条时画面才不会空成四团光。
   用屏幕坐标 + 一点点视差跟随相机，比放进 3D 里便宜得多。 */
const STARFIELD = (() => {
  const rng = mulberry32(hashString('memory-starfield'))
  const stars = []
  const count = 640
  for (let i = 0; i < count; i += 1) {
    stars.push({
      x: rng(),
      y: rng(),
      r: 0.4 + rng() * 1.1,
      a: 0.1 + rng() * 0.28,
      warm: rng() < 0.22,
      depth: 0.3 + rng() * 0.7
    })
  }
  return stars
})()

function drawStarfield(width, height) {
  if (!ctx) return
  const yawShift = Math.sin(camera.yaw) * 26
  const pitchShift = Math.sin(camera.pitch) * 16
  ctx.globalCompositeOperation = 'lighter'
  for (const star of STARFIELD) {
    const x = (star.x * width + yawShift * star.depth + width) % width
    const y = (star.y * height + pitchShift * star.depth + height) % height
    ctx.fillStyle = star.warm
      ? `rgba(255, 224, 178, ${star.a})`
      : `rgba(206, 224, 255, ${star.a})`
    ctx.beginPath()
    ctx.arc(x, y, star.r, 0, TAU)
    ctx.fill()
  }
  ctx.globalCompositeOperation = 'source-over'
}

function render(now) {
  const { width, height } = viewport
  if (!width || !height) return

  drawBackground()
  drawStarfield(width, height)

  pickables.length = 0
  const labels = []
  const fadeFor = (depth) => depthFade(depth)

  /* 底部扇形记忆库：例图里记忆库是一面巨大的锥形点阵 —— 上方收成一点、下方铺开成扇面。
     这里在 3D 里画长期记忆库下方的一段圆锥，跟着相机一起转，不会像贴图一样漂。
     要的是密度不是亮度：单个点很小很淡，靠数量铺出扇面。 */
  const ltmRegion = graph.value.regionMap.get('ltm')
  if (ltmRegion) {
    ctx.globalCompositeOperation = 'lighter'
    const sectorById = new Map(MEMORY_SECTORS.map((sector) => [sector.id, sector]))
    const leftColor = sectorById.get('FACT')?.color || '#ffd166'
    const rightColor = sectorById.get('EMOTION')?.color || '#ff8fab'
    const total = Math.max(1, stats.value.total)
    const count = Math.min(560, Math.max(180, Math.ceil(total * 1.7)))
    const rng = mulberry32(hashString('memory-library-fan'))
    for (let i = 0; i < count; i += 1) {
      const depth = 92 + Math.sqrt((i + 0.5) / count) * 180
      const angle = rng() * TAU
      const spread = depth * (0.55 + rng() * 0.5)
      const pos3 = [
        ltmRegion.c[0] + Math.cos(angle) * spread,
        ltmRegion.c[1] + depth * 0.92,
        ltmRegion.c[2] + Math.sin(angle) * spread * 0.6
      ]
      const point = projectPoint(pos3, camera, viewport)
      if (!point.visible) continue
      const color = pos3[0] < ltmRegion.c[0] ? leftColor : rightColor
      glow(point.x, point.y, 0.7 + (i % 3) * 0.25, color, 0.09 + (i % 4) * 0.022)
    }
    ctx.globalCompositeOperation = 'source-over'
  }

  /* 脑壳只留微弱星点。整圈赤道会像一条横穿画布的行星轨道，抢过记忆关系层级。 */
  ctx.globalCompositeOperation = 'lighter'
  for (let i = 0; i < SHELL_DOTS.length; i += 2) {
    const point = projectPoint(SHELL_DOTS[i], camera, viewport)
    const alpha = (0.03 + 0.05 * fadeFor(point.depth)) * (point.depth > 0 ? 0.5 : 0.8)
    glow(point.x, point.y, 0.85, '#9fb8d8', alpha)
  }
  ctx.globalCompositeOperation = 'source-over'

  /* 脑区流程是解释层：默认隐藏，用户点选脑区后才显示它相邻的流程线。 */
  for (const flow of graph.value.flows) {
    if (!focusedRegion.value) continue
    const segment = flowSegments.get(flow.id)
    if (!segment) continue
    const a = projectPoint(segment.a, camera, viewport)
    const b = projectPoint(segment.b, camera, viewport)
    if (!a.visible || !b.visible) continue
    const touched = flow.from === focusedRegion.value || flow.to === focusedRegion.value
    if (!touched) continue

    drawBeam(a, b, segment.fromColor, segment.toColor, 0.42, {
      dashed: flow.dash,
      arrow: true,
      arrowColor: segment.toColor,
      arrowAlpha: 0.38
    })

    if (!compact.value) {
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

  /* 簇关系线在局部光圈后、记忆光点前绘制，避免线条压住节点 */

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

  /* 记忆星点按角色/类型组成局部簇；光圈只包住各自成员，不再用四个大圈把全图切成花瓣。 */
  const cutoff = replayIndex.value >= 0 ? (timeline.value[replayIndex.value]?.at ?? 0) + 86400000 : Infinity
  const nodes = graph.value.memoryNodes
    .map((node) => ({ node, point: projectPoint(node.pos, camera, viewport) }))
    .sort((a, b) => b.point.depth - a.point.depth)
  const selectedNode = graph.value.memoryNodes.find(
    (node) => String(node.memoryId) === String(props.selectedMemoryId)
  )
  const activeNode = hover.value?.kind === 'memory' ? hover.value.node : selectedNode
  const focusedClusterId = activeNode?.clusterId || ''
  // 只是划过某颗星时，别把其余三簇整片熄掉：那种「整张图突然黑掉」的反馈比高亮本身更抢眼。
  // 真正收敛全图只在「选中一条记忆」时发生。
  const hoverOnly = Boolean(hover.value?.kind === 'memory' && !selectedNode)
  const clusterViews = graph.value.clusters.map((cluster) => {
    const members = nodes.filter((item) => item.node.clusterId === cluster.id && item.point.visible)
    const hub = projectPoint(cluster.pos, camera, viewport)
    if (!members.length) return { cluster, members, hub, cx: hub.x, cy: hub.y, rx: 18 }
    const cx = members.reduce((sum, item) => sum + item.point.x, 0) / members.length
    const cy = members.reduce((sum, item) => sum + item.point.y, 0) / members.length
    let rx = 18
    for (const item of members) {
      rx = Math.max(rx, Math.hypot(item.point.x - cx, (item.point.y - cy) / 0.82) + Math.max(3, item.node.size * item.point.scale * 1.2))
    }
    return { cluster, members, hub, cx, cy, rx: rx + 8 }
  })
  const sectorHubViews = graph.value.sectorHubs.map((sectorHub) => ({
    sectorHub,
    point: projectPoint(sectorHub.pos, camera, viewport)
  }))

  /* 簇光圈：四瓣各自一圈，像参考例图里「长期记忆库」周围的光晕，不再用大椭圆环 */
  for (const view of clusterViews) {
    if (!view.members.length) continue
    const focused = focusedClusterId === view.cluster.id
    const dimmed = Boolean(focusedClusterId && !focused)
    const alpha = dimmed ? (hoverOnly ? 0.02 : 0.006) : focused ? 0.11 : 0.042
    ctx.save()
    ctx.translate(view.cx, view.cy)
    ctx.scale(1, 0.82)
    const gradient = ctx.createRadialGradient(0, 0, view.rx * 0.2, 0, 0, view.rx)
    gradient.addColorStop(0, rgba(view.cluster.color, alpha * 0.7))
    gradient.addColorStop(0.72, rgba(view.cluster.color, alpha))
    gradient.addColorStop(1, rgba(view.cluster.color, 0))
    ctx.fillStyle = gradient
    ctx.beginPath()
    ctx.arc(0, 0, view.rx, 0, TAU)
    ctx.fill()
    ctx.restore()
  }

  const clusterLabels = clusterViews.filter((view) => !view.cluster.central && view.hub.visible)

  /* 簇 hub：中心到少量语义簇的主干止于这里，不再穿过整张图直达每颗记忆。
     用多层光晕让 hub 更醒目，像例图里的关键节点。 */
  for (const view of clusterViews) {
    if (!view.hub.visible) continue
    const focused = focusedClusterId === view.cluster.id
    const dimmed = Boolean(focusedClusterId && !focused)
    const radius = Math.min(6.5, 3.2 + Math.sqrt(view.cluster.count) * 0.32)
    glowMulti(view.hub.x, view.hub.y, radius * 1.5, view.cluster.color, dimmed ? (hoverOnly ? 0.16 : 0.03) : focused ? 0.58 : 0.32)
    ctx.beginPath()
    ctx.arc(view.hub.x, view.hub.y, radius, 0, TAU)
    ctx.fillStyle = rgba(view.cluster.color, dimmed ? (hoverOnly ? 0.42 : 0.12) : 0.72)
    ctx.fill()
    ctx.beginPath()
    ctx.arc(view.hub.x, view.hub.y, Math.max(1, radius * 0.38), 0, TAU)
    ctx.fillStyle = 'rgba(255,255,255,0.92)'
    ctx.fill()
  }

  /* 第一层：中枢（检索，在顶部）→ 类型枢纽，是金字塔的主光束，要粗要亮。
     例图里这些线像光纤一样发光，所以加 glow 叠加。 */
  for (const edge of sectorSpines.value) {
    const from = projectPoint(edge.from, camera, viewport)
    const to = projectPoint(edge.to, camera, viewport)
    if (!from.visible || !to.visible) continue
    const focused = activeNode?.sector === edge.sectorHub.sector
    const alpha = focusedClusterId ? (focused ? 0.62 : hoverOnly ? 0.14 : 0.02) : 0.42
    // 主光束先垫一层宽而淡的辉光：例图里这几根线是整张图的光纤主干
    ctx.globalCompositeOperation = 'lighter'
    ctx.strokeStyle = rgba('#fff1c9', alpha * 0.16)
    ctx.lineWidth = 10
    ctx.beginPath()
    ctx.moveTo(from.x, from.y)
    ctx.lineTo(to.x, to.y)
    ctx.stroke()
    drawBeam(from, to, '#fff1c9', edge.sectorHub.color, alpha * 1.5, { arrow: false })
    // 沿线加发光点，模拟光纤效果
    const segments = 8
    for (let i = 1; i < segments; i++) {
      const t = i / segments
      const x = from.x + (to.x - from.x) * t
      const y = from.y + (to.y - from.y) * t
      const fade = Math.sin(t * Math.PI)
      glow(x, y, 0.9, edge.sectorHub.color, alpha * fade * 0.35)
    }
  }

  /* 编织层：类型枢纽彼此相连，做出例图中间那片「网」。刻意画得极淡，
     读起来是背景的组织感，而不是某条具体记忆的关联。 */
  for (const edge of meshEdges.value) {
    const a = projectPoint(edge.a, camera, viewport)
    const b = projectPoint(edge.b, camera, viewport)
    const c = projectPoint(edge.control, camera, viewport)
    if (!a.visible || !b.visible || !c.visible) continue
    const alpha = focusedClusterId ? (hoverOnly ? 0.05 : 0.012) : 0.08
    ctx.globalCompositeOperation = 'lighter'
    ctx.strokeStyle = rgba(edge.colorA, alpha * 0.9)
    ctx.lineWidth = 1.6
    ctx.beginPath()
    ctx.moveTo(a.x, a.y)
    ctx.quadraticCurveTo(c.x, c.y, b.x, b.y)
    ctx.stroke()
    ctx.strokeStyle = rgba(edge.colorB, alpha * 1.6)
    ctx.lineWidth = 0.55
    ctx.beginPath()
    ctx.moveTo(a.x, a.y)
    ctx.quadraticCurveTo(c.x, c.y, b.x, b.y)
    ctx.stroke()
    ctx.globalCompositeOperation = 'source-over'
  }

  for (const edge of clusterSpines.value) {
    const from = projectPoint(edge.from, camera, viewport)
    const to = projectPoint(edge.to, camera, viewport)
    if (!from.visible || !to.visible) continue
    const focused = focusedClusterId === edge.cluster.id
    const alpha = focusedClusterId ? (focused ? 0.6 : hoverOnly ? 0.11 : 0.02) : 0.32
    ctx.globalCompositeOperation = 'source-over'
    ctx.strokeStyle = rgba(edge.cluster.color, alpha)
    ctx.lineWidth = focused ? 1.5 : 1.1
    ctx.beginPath()
    ctx.moveTo(from.x, from.y)
    ctx.lineTo(to.x, to.y)
    ctx.stroke()
    // 加发光点
    const segments = 6
    for (let i = 1; i < segments; i++) {
      const t = i / segments
      const x = from.x + (to.x - from.x) * t
      const y = from.y + (to.y - from.y) * t
      const fade = Math.sin(t * Math.PI)
      glow(x, y, 0.7, edge.cluster.color, alpha * fade * 0.3)
    }
    ctx.globalCompositeOperation = 'lighter'
  }

  for (const { sectorHub, point } of sectorHubViews) {
    if (!point.visible) continue
    const focused = activeNode?.sector === sectorHub.sector
    const radius = Math.min(7, 4 + Math.sqrt(sectorHub.count) * 0.32)
    glowMulti(point.x, point.y, radius * 1.5, sectorHub.color, focused ? 0.55 : 0.38)
    ctx.beginPath()
    ctx.arc(point.x, point.y, radius + 2.2, 0, TAU)
    ctx.strokeStyle = rgba(sectorHub.color, focused ? 0.65 : 0.45)
    ctx.lineWidth = 1
    ctx.stroke()
    ctx.beginPath()
    ctx.arc(point.x, point.y, radius, 0, TAU)
    ctx.fillStyle = rgba(sectorHub.color, 0.92)
    ctx.fill()
    ctx.beginPath()
    ctx.arc(point.x, point.y, Math.max(1, radius * 0.34), 0, TAU)
    ctx.fillStyle = 'rgba(255,255,255,0.92)'
    ctx.fill()
  }

  const edgeGroups = new Map()
  // 画线前必须清掉阴影：之前给标签设的 shadowColor/shadowBlur 会跟着每条 stroke 一起画，
  // 一条亮线底下垫一层暗影，远看就是一根根黑刺。
  ctx.shadowBlur = 0
  ctx.shadowColor = 'transparent'
  for (const edge of memoryEdges.value) {
    if (!edgeGroups.has(edge.clusterId)) edgeGroups.set(edge.clusterId, [])
    edgeGroups.get(edge.clusterId).push(edge)
  }
  // `trim`：只画这条线靠节点那一小段。全员都从簇心拉一根长线，远端的成员会拖出
  // 一把「黑刺」，把星团画成蒲公英；短须才是树突的质感。
  const strokeEdges = (list, color, alpha, width, trim = 1) => {
    if (!list.length) return
    ctx.beginPath()
    for (const edge of list) {
      const from = projectPoint(edge.from, camera, viewport)
      const to = projectPoint(edge.to, camera, viewport)
      if (!from.visible || !to.visible) continue
      const x0 = from.x + (to.x - from.x) * (1 - trim)
      const y0 = from.y + (to.y - from.y) * (1 - trim)
      ctx.moveTo(x0, y0)
      ctx.lineTo(to.x, to.y)
    }
    ctx.strokeStyle = rgba(color, alpha)
    ctx.lineWidth = width
    ctx.stroke()
  }

  for (const [clusterId, edges] of edgeGroups) {
    const focused = focusedClusterId === clusterId
    const dim = Boolean(focusedClusterId && !focused)
    const color = edges[0]?.cluster.color || '#9fb8d8'
    // 归属边是「这颗星属于这一簇」的事实，全员都画；重要成员更亮更粗，构成主次。
    const strong = edges.filter((edge) => edge.representative)
    const weak = edges.filter((edge) => !edge.representative)
    const alpha = dim ? (hoverOnly ? 0.07 : 0.006) : focused ? 0.34 : compact.value ? 0.13 : 0.2

    // 光纤感：宽而淡的光晕 + 细而亮的芯，两遍叠出「会发光的线」
    // 注意参数顺序是 (list, color, alpha, width, trim) —— 别把颜色写成透明度，
    // parseColor(0.2) 会退化成黑色，整片星团就会被画成蒲公英。
    strokeEdges(weak, color, alpha * 0.26, 2, 0.3)
    strokeEdges(weak, color, alpha * 0.85, 0.55, 0.3)
    strokeEdges(strong, color, alpha * 0.5, 2.8, 1)
    strokeEdges(strong, color, alpha * 1.5, 0.95, 1)

    const activeEdge = edges.find((edge) => String(edge.node.memoryId) === String(activeNode?.memoryId))
    if (activeEdge) {
      const from = projectPoint(activeEdge.from, camera, viewport)
      const to = projectPoint(activeEdge.to, camera, viewport)
      if (from.visible && to.visible) {
        ctx.strokeStyle = rgba(activeEdge.node.color, 0.78)
        ctx.lineWidth = 1.25
        ctx.beginPath()
        ctx.moveTo(from.x, from.y)
        ctx.lineTo(to.x, to.y)
        ctx.stroke()
      }
    }
  }

  /* 真实共源边只出现在两条记忆共享 sourceMsgId 时；虚线和冷白色与簇归属线区分。 */
  for (const edge of relatedEdges.value) {
    const from = projectPoint(edge.from, camera, viewport)
    const to = projectPoint(edge.to, camera, viewport)
    if (!from.visible || !to.visible) continue
    const isFocused =
      String(edge.fromId) === String(activeNode?.memoryId) || String(edge.toId) === String(activeNode?.memoryId)
    ctx.save()
    ctx.setLineDash([2, 3])
    ctx.strokeStyle = `rgba(220, 235, 255, ${isFocused ? 0.62 : focusedClusterId ? (hoverOnly ? 0.09 : 0.018) : 0.16})`
    ctx.lineWidth = isFocused ? 1 : 0.65
    ctx.beginPath()
    ctx.moveTo(from.x, from.y)
    ctx.lineTo(to.x, to.y)
    ctx.stroke()
    ctx.restore()
  }

  for (const { node, point } of nodes) {
    if (!point.visible) continue
    const isSelected = String(node.memoryId) === String(props.selectedMemoryId)
    const isHovered = hover.value?.kind === 'memory' && hover.value.id === node.memoryId
    const isHighlighted = highlight.value.has(node.id)
    const dimmed =
      (focusedRegion.value && focusedRegion.value !== 'ltm') ||
      (focusedClusterId && node.clusterId !== focusedClusterId) ||
      (node.at > cutoff) ||
      (highlight.value.size > 0 && !isHighlighted)

    const base = depthFade(point.depth)
    const strength = Number.isFinite(node.strength) ? node.strength : 1
    // 静音是「别再提了」，不是「没有过」：留在图上，但暗到不会先被看见。
    // 别的簇在「划过」时只压四成，在「选中」时压到一成——别让一次划过把整张星图熄掉。
    const dimFactor = dimmed ? (node.clusterId !== focusedClusterId && hoverOnly ? 0.42 : 0.12) : 1
    const alpha = mutedAlpha(
      dimmed ? base * dimFactor : base * (0.62 + node.importance * 0.28) * (0.58 + 0.42 * strength),
      node.muted
    )
    const radius = Math.max(1.25, node.size * point.scale * (node.muted ? 0.78 : 1))

    // 大光圈：参考例图里每颗记忆都是一颗带 halo 的星
    glowMulti(point.x, point.y, radius * (isSelected || isHovered ? 3.2 : 2.1), node.color, alpha * 0.82)
    ctx.globalCompositeOperation = 'source-over'
    ctx.beginPath()
    ctx.arc(point.x, point.y, Math.max(0.95, radius * 0.74), 0, TAU)
    ctx.fillStyle = rgba(node.color, dimmed ? (hoverOnly ? 0.38 : 0.22) : 0.55 + 0.4 * strength)
    ctx.fill()
    ctx.beginPath()
    ctx.arc(point.x, point.y, Math.max(0.42, radius * 0.3), 0, TAU)
    ctx.fillStyle = rgba('#ffffff', dimmed ? 0.28 : 0.9)
    ctx.fill()

    // 钉住的记忆加一圈暖金细环：它不衰减，图上也不该显得"快没了"。
    if (node.pinned) {
      ctx.beginPath()
      ctx.arc(point.x, point.y, radius + 2.8, 0, TAU)
      ctx.strokeStyle = rgba('#fff1c9', dimmed ? 0.18 : 0.56)
      ctx.lineWidth = 1
      ctx.stroke()
    }
    ctx.globalCompositeOperation = 'lighter'

    if (isSelected || isHovered || isHighlighted) {
      ctx.globalCompositeOperation = 'source-over'
      ctx.strokeStyle = rgba(node.color, isSelected ? 0.9 : 0.56)
      ctx.lineWidth = 1
      ctx.beginPath()
      ctx.arc(point.x, point.y, radius * 2.8 + Math.sin(now * 0.005) * 1.2, 0, TAU)
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

  /* 簇标签贴近各自的 hub；小簇默认不占字，悬停/选中时再显示，避免标签网。 */
  labelBoxes.length = 0
  reserveOverlayBoxes()
  const orderedClusterLabels = [...clusterLabels].sort((a, b) => {
    const aFocused = a.cluster.id === focusedClusterId ? 1 : 0
    const bFocused = b.cluster.id === focusedClusterId ? 1 : 0
    return bFocused - aFocused || b.cluster.count - a.cluster.count
  })
  for (const view of orderedClusterLabels) {
    const focused = view.cluster.id === focusedClusterId
    if (view.cluster.count < 2 && !focused) continue
    const sectorName = t(view.cluster.nameKey)
    // 数字用统计口径（全量），不用画出来的节点数：截断后两者会不一致，同一屏里对不上很怪
    const sectorTotal = stats.value.bySector?.[view.cluster.sector] ?? view.cluster.count
    const text = [sectorName, String(sectorTotal)].filter(Boolean).join(' · ')
    const lx = clamp(view.hub.x, 44, viewport.width - 44)
    // 标签挂在簇的正下方：挂在簇上方会被大量光点压住，也会和别的簇串味
    const ly = Math.min(viewport.height - 16, view.cy + Math.min(view.rx * 0.78, 104) + 10)
    const size = compact.value ? 10.5 : 12.5
    const box = textBox(text, lx, ly, { size, weight: 700 })
    const padded = { x1: box.x1 - 8, y1: box.y1 - 5, x2: box.x2 + 8, y2: box.y2 + 5 }
    if (boxOverlaps(padded, 3)) continue
    ctx.save()
    ctx.shadowColor = rgba(view.cluster.color, 0.55)
    ctx.shadowBlur = 14
    drawLabelText(text, lx, ly, {
      color: '#f4f8ff',
      alpha: focusedClusterId && !focused ? (hoverOnly ? 0.55 : 0.22) : focused ? 1 : 0.92,
      size,
      weight: 700,
      plate: true
    })
    ctx.restore()
    reserveBox(padded)
  }

  /* 脑区：一颗大光核 + 一圈光环 + 发光大字标签。参考例图里脑区是带光晕的锚点。 */
  const sortedRegions = graph.value.regions
    .map((region) => ({ region, point: projectPoint(region.c, camera, viewport) }))
    .sort((a, b) => b.point.depth - a.point.depth)

  for (const { region, point } of sortedRegions) {
    if (!point.visible) continue
    const dimmed = focusedRegion.value && focusedRegion.value !== region.id
    const base = depthFade(point.depth)
    const lit = region.lit && !dimmed
    const radius = Math.max(4, (region.r[0] + region.r[1]) * 0.5 * point.scale)
    const orb = Math.max(4.5, radius * 0.28)
    const breathe = reducedMotion.value || !region.lit ? 1 : 1 + Math.sin(now * 0.0016 + region.c[0]) * 0.04

    // 大光晕：用多层光晕，像例图里的关键节点
    glowMulti(point.x, point.y, orb * 3.2 * breathe, region.color, (lit ? 0.32 : 0.1) * base)
    glowMulti(point.x, point.y, orb * 1.3, '#ffffff', (lit ? 0.28 : 0.1) * base)
    // 外圈光环
    ctx.globalCompositeOperation = 'source-over'
    ctx.beginPath()
    ctx.arc(point.x, point.y, orb * 1.15, 0, TAU)
    ctx.strokeStyle = rgba(region.color, (lit ? 0.42 : 0.12) * base)
    ctx.lineWidth = 1
    ctx.stroke()
    ctx.globalCompositeOperation = 'lighter'

    ctx.globalCompositeOperation = 'lighter'
    pickables.push({
      kind: 'region',
      id: region.id,
      region,
      x: point.x,
      y: point.y,
      hitRadius: Math.max(16, orb * 1.4),
      weight: 1
    })

    labels.push({ region, point, radius: orb, dimmed })
  }

  /* 脑区标签：参考例图里是发光大字，默认全画，按 labelSide 左右/上下放置。 */
  ctx.globalCompositeOperation = 'source-over'
  const hoveredRegionId = hover.value?.kind === 'region' ? hover.value.id : ''
  for (const { region, point, radius, dimmed } of labels.sort((a, b) => b.point.depth - a.point.depth)) {
    // 空的脑区不挂字：0 条的标签只会和别的标签抢位置
    if (!region.count) continue
    const labelAlpha = dimmed ? 0.16 : region.lit ? 0.95 : 0.5
    const name = t(region.nameKey)
    const nameSize = compact.value ? 11 : 13
    const countSize = compact.value ? 8.5 : 9.5
    ctx.strokeStyle = rgba(region.color, labelAlpha * 0.32)
    ctx.lineWidth = 1

    // 长期记忆库是四瓣的整体，名字挂正中间，光点绕着它铺开
    if (region.labelSide === 'center') {
      const labelY = point.y - 10
      const nameBox = textBox(name, point.x, labelY, { size: nameSize, weight: 700 })
      if (boxOverlaps(nameBox)) continue
      // 发光标签：例图里核心标签带同色光晕
      ctx.save()
      ctx.shadowColor = rgba(region.color, labelAlpha * 0.6)
      ctx.shadowBlur = 12
      drawLabelText(name, point.x, labelY, {
        color: '#f2f6ff',
        alpha: labelAlpha,
        size: nameSize,
        weight: 700,
        plate: true
      })
      ctx.restore()
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
      const labelX = point.x + radius * 0.9 + 12
      ctx.beginPath()
      ctx.moveTo(point.x + radius * 0.7, point.y)
      ctx.lineTo(labelX - 5, point.y)
      ctx.stroke()
      const nameBox = textBox(name, labelX, point.y, { size: nameSize, weight: 700, align: 'left' })
      if (boxOverlaps(nameBox)) continue
      const placed = drawLabelText(name, labelX, point.y, {
        color: '#f2f6ff',
        alpha: labelAlpha,
        size: nameSize,
        weight: 700,
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

    // 左侧脑区（input / relation / introspect）标签挂到光核左边
    if (region.labelSide === 'left') {
      const labelX = point.x - radius * 0.9 - 12
      ctx.beginPath()
      ctx.moveTo(point.x - radius * 0.7, point.y)
      ctx.lineTo(labelX + 5, point.y)
      ctx.stroke()
      const nameBox = textBox(name, labelX, point.y, { size: nameSize, weight: 700, align: 'right' })
      if (boxOverlaps(nameBox)) continue
      const placed = drawLabelText(name, labelX, point.y, {
        color: '#f2f6ff',
        alpha: labelAlpha,
        size: nameSize,
        weight: 700,
        align: 'right'
      })
      reserveBox(nameBox)
      const countBox = textBox(String(region.count), labelX - placed - 5, point.y, {
        size: countSize,
        align: 'right'
      })
      if (!boxOverlaps(countBox)) {
        drawLabelText(String(region.count), labelX - placed - 5, point.y, {
          color: region.color,
          alpha: labelAlpha * 0.85,
          size: countSize,
          align: 'right'
        })
        reserveBox(countBox)
      }
      continue
    }
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
    // 相机转到脑区背后时 scale 会变成负数，ellipse 收到负半径会直接抛 IndexSizeError，
    // 一抛整帧后面的绘制全部作废（表现为画面卡在上一帧、像是"画不出来"）。
    if (!point.visible || point.scale <= 0) {
      pulses.splice(i, 1)
      continue
    }
    const radius = Math.max(0.5, (12 + progress * 44) * point.scale)
    ctx.globalCompositeOperation = 'source-over'
    ctx.strokeStyle = rgba(pulse.color, (1 - progress) * 0.32 * pulse.strength)
    ctx.lineWidth = 1.1
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

/**
 * 图例、工具条、右侧面板、底部回放条都是 DOM 覆盖层，画布并不知道它们占了哪儿。
 * 不预留的话，脑区标签会直接压在「检索中枢」那几行图例上，字叠在一起谁也读不清。
 * 这里把它们的实际矩形登记成占位盒，标签就会自己让开。
 */
function reserveOverlayBoxes() {
  const root = rootRef.value
  const canvas = canvasRef.value
  if (!root || !canvas) return
  const origin = canvas.getBoundingClientRect()
  for (const selector of ['.brain-legend', '.brain-tools', '.brain-panel', '.brain-scrub']) {
    const el = root.querySelector(selector)
    if (!el) continue
    const rect = el.getBoundingClientRect()
    if (!rect.width || !rect.height) continue
    labelBoxes.push({
      x1: rect.left - origin.left,
      y1: rect.top - origin.top,
      x2: rect.right - origin.left,
      y2: rect.bottom - origin.top
    })
  }
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
