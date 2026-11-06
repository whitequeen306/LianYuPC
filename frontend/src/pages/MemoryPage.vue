<template>
  <div class="memory-page stagger-container" :class="{ 'is-brain': view === 'brain' }">
    <header class="page-header">
      <h1 class="page-title">{{ t('memory.title') }}</h1>
      <p class="page-desc">{{ t('memory.desc') }}</p>
    </header>

    <div class="filter-row stagger-item">
      <el-select
        v-model="filterCharId"
        :placeholder="t('memory.allCharacters')"
        clearable
        class="filter-select"
        @change="fetchAll"
      >
        <el-option
          v-for="c in charactersStore.list"
          :key="c.id"
          :label="c.name"
          :value="c.id"
        />
      </el-select>

      <div class="view-toggle" role="group">
        <button
          type="button"
          class="view-toggle__btn"
          :class="{ 'is-active': view === 'brain' }"
          @click="setView('brain')"
        >
          <el-icon :size="14"><Share /></el-icon>
          <span>{{ t('memory.viewBrain') }}</span>
        </button>
        <button
          type="button"
          class="view-toggle__btn"
          :class="{ 'is-active': view === 'list' }"
          @click="setView('list')"
        >
          <el-icon :size="14"><List /></el-icon>
          <span>{{ t('memory.viewList') }}</span>
        </button>
        <button
          type="button"
          class="view-toggle__btn"
          :class="{ 'is-active': view === 'episodes' }"
          @click="setView('episodes')"
        >
          <el-icon :size="14"><Notebook /></el-icon>
          <span>{{ t('memory.viewEpisodes') }}</span>
        </button>
      </div>

      <el-button :icon="RefreshRight" :loading="loading" class="refresh-btn" @click="fetchAll">
        {{ t('memory.refresh') }}
      </el-button>
    </div>

    <div v-if="loading" class="loading-state">
      <el-icon class="is-loading" :size="24"><Loading /></el-icon>
      <span>{{ t('common.loading') }}</span>
    </div>

    <!-- 星图：默认视图 -->
    <MemoryBrainMap
      v-else-if="view === 'brain'"
      class="stagger-item"
      :memories="memories"
      :characters="charactersStore.list"
      :states="visibleStates"
      :diaries="visibleDiaries"
      :selected-memory-id="detailId"
      @select-memory="openDetail"
    />

    <!-- 列表视图：保留原来的分组卡片 -->
    <template v-else-if="view === 'list'">
      <div v-if="memories.length === 0" class="empty-state glass stagger-item">
        <div class="empty-icon">
          <el-icon :size="40"><Collection /></el-icon>
        </div>
        <h3>{{ t('memory.empty') }}</h3>
        <p>{{ t('memory.emptyDesc') }}</p>
      </div>

      <div v-else class="memory-groups">
        <div
          v-for="group in groupedMemories"
          :key="group.charId"
          class="memory-group glass stagger-item"
        >
          <div class="group-header">
            <div class="group-char-info">
              <div class="group-char-avatar">
                <CharacterAvatarImg
                  :character-id="group.charId"
                  :characters="charactersStore.list"
                  :alt="group.charName"
                  :icon-size="16"
                />
              </div>
              <span class="group-char-name">{{ group.charName }}</span>
              <span class="group-count">{{ t('memory.count', { count: group.items.length }) }}</span>
            </div>
          </div>

          <div class="group-items">
            <div
              v-for="(mem, idx) in group.items"
              :key="mem.id"
              class="memory-card"
              :style="{ animationDelay: `${idx * 0.03}s` }"
            >
              <div class="mem-header">
                <span class="mem-time">{{ formatDate(mem.createdAt) }}</span>
                <span v-if="layerLabel(mem.layer)" class="mem-layer">{{ layerLabel(mem.layer) }}</span>
              </div>

              <div class="mem-summary">{{ mem.summary }}</div>

              <div v-if="mem.sourceMsgIds && mem.sourceMsgIds.length" class="mem-sources">
                <el-button text size="small" @click="toggleSources(mem.id)">
                  <el-icon :size="14">
                    <component :is="expandedId === mem.id ? ArrowDown : ArrowRight" />
                  </el-icon>
                  {{
                    expandedId === mem.id
                      ? t('memory.collapseSources')
                      : t('memory.viewSources', { count: mem.sourceMsgIds.length })
                  }}
                </el-button>

                <MemorySourceList
                  v-if="expandedId === mem.id"
                  class="mem-source-list"
                  :messages="sourceMessages"
                  :loading="sourceLoading"
                />
              </div>

              <div class="mem-footer">
                <el-button text size="small" @click="openDetail(mem)">
                  {{ t('memory.detail') }}
                </el-button>
                <el-button text :icon="Delete" size="small" class="btn-delete" @click="removeMemory(mem)">
                  {{ t('common.delete') }}
                </el-button>
              </div>
            </div>
          </div>
        </div>
      </div>
    </template>

    <!-- 共同经历：场景关闭时抽出的原话，事实层看不到的那一层 -->
    <template v-else>
      <div v-if="visibleEpisodes.length === 0" class="empty-state glass stagger-item">
        <div class="empty-icon">
          <el-icon :size="40"><Notebook /></el-icon>
        </div>
        <h3>{{ t('memory.episodesEmpty') }}</h3>
        <p>{{ t('memory.episodesEmptyDesc') }}</p>
      </div>

      <div v-else class="episode-list">
        <article
          v-for="ep in visibleEpisodes"
          :key="ep.id"
          class="episode-card glass stagger-item"
        >
          <header class="episode-head">
            <span class="episode-time">{{ formatDate(ep.eventTime || ep.createdAt) }}</span>
            <span class="episode-char">{{ ep.characterName }}</span>
            <span v-if="ep.openLoop" class="episode-loop">{{ t('memory.episodeOpenLoop') }}</span>
          </header>
          <blockquote v-if="ep.quote" class="episode-quote">{{ ep.quote }}</blockquote>
          <p class="episode-summary">{{ ep.summary }}</p>
        </article>
      </div>
    </template>

    <!-- 记忆详情抽屉（星图点击 / 列表详情共用） -->
    <Teleport to="body">
      <Transition name="mem-sheet">
        <div v-if="detailVisible" class="mem-sheet-mask" @click.self="closeDetail">
          <aside class="mem-sheet">
            <header class="mem-sheet__head">
              <span class="mem-sheet__sector" :style="{ color: sectorColor(detailMemory) }">
                {{ sectorLabel(detailMemory) }}
              </span>
              <span v-if="layerLabel(detailMemory?.layer)" class="mem-sheet__layer">
                {{ layerLabel(detailMemory?.layer) }}
              </span>
              <span v-if="detailFadeLabel" class="mem-sheet__fade">{{ detailFadeLabel }}</span>
              <span v-if="detailRecallLabel" class="mem-sheet__recalled">{{ detailRecallLabel }}</span>
              <button type="button" class="mem-sheet__close" @click="closeDetail">
                <el-icon :size="16"><Close /></el-icon>
              </button>
            </header>

            <p v-if="detailMemory?.quote" class="mem-sheet__quote">
              <em>{{ t('memory.quoteLabel') }}</em>{{ detailMemory.quote }}
            </p>
            <p class="mem-sheet__summary">{{ detailMemory?.summary || '' }}</p>

            <div class="mem-sheet__meta">
              <span>{{ characterName(detailMemory) }}</span>
              <span>{{ formatDate(detailMemory?.createdAt) }}</span>
            </div>

            <div class="mem-sheet__weight">
              <span>{{ t('memory.importance') }}</span>
              <div class="mem-sheet__bar">
                <i :style="{ width: `${importancePercent(detailMemory)}%` }" />
              </div>
              <b>{{ importancePercent(detailMemory) }}</b>
            </div>

            <h4 class="mem-sheet__title">{{ t('memory.sources') }}</h4>
            <MemorySourceList :messages="detailSources" :loading="detailLoading" />

            <footer class="mem-sheet__foot">
              <el-button
                text
                class="mem-sheet__mute"
                :class="{ 'is-muted': detailMemory?.muted }"
                :loading="muteLoading"
                @click="toggleMute(detailMemory)"
              >
                {{ detailMemory?.muted ? t('memory.unmute') : t('memory.mute') }}
              </el-button>
              <el-button
                text
                class="mem-sheet__pin"
                :class="{ 'is-pinned': detailMemory?.pinned }"
                :loading="pinLoading"
                @click="togglePin(detailMemory)"
              >
                {{ detailMemory?.pinned ? t('memory.unpin') : t('memory.pin') }}
              </el-button>
              <el-button text class="mem-sheet__delete" @click="removeMemory(detailMemory)">
                {{ t('common.delete') }}
              </el-button>
            </footer>
          </aside>
        </div>
      </Transition>
    </Teleport>
  </div>
</template>

<script setup>
import { computed, onActivated, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useCharactersStore } from '@/stores/characters'
import {
  listMemories,
  listEpisodes,
  getMemory,
  deleteMemory,
  setMemoryPinned,
  setMemoryMuted
} from '@/api/memory'
import { listCharacterStates, listAllDiaries } from '@/api/characterState'
import {
  Loading,
  Collection,
  ArrowDown,
  ArrowRight,
  RefreshRight,
  Delete,
  Share,
  List,
  Close
} from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import CharacterAvatarImg from '@/components/CharacterAvatarImg.vue'
import MemoryBrainMap from '@/components/memory/MemoryBrainMap.vue'
import MemorySourceList from '@/components/memory/MemorySourceList.vue'
import { MEMORY_SECTORS } from '@/components/memory/memoryBrainModel'
import { formatSmartTime } from '@/utils/feedTime'

defineOptions({ name: 'MemoryPage' })

const VIEW_STORAGE_KEY = 'lianyu-memory-view'
const VIEWS = ['brain', 'list', 'episodes']

const { t, locale } = useI18n()
const charactersStore = useCharactersStore()

const memories = ref([])
const loading = ref(true)
const filterCharId = ref(null)
const expandedId = ref(null)
const sourceLoading = ref(false)
const sourceMessages = ref([])

const states = ref([])
const diaries = ref([])
const episodes = ref([])

const view = ref(readView())
const detailVisible = ref(false)
const detailId = ref(null)
const detailMemory = ref(null)
const detailSources = ref([])
const detailLoading = ref(false)
const pinLoading = ref(false)
const muteLoading = ref(false)

const LAYER_NAMES = ['core', 'fact', 'episode', 'reflection']

/** 层标签：事实/经历/印象是三种不同的「记得」，混在一起看会误判她的态度。 */
function layerLabel(layer) {
  return LAYER_NAMES.includes(layer) ? t(`memory.layer.${layer}`) : ''
}

const detailFadeLabel = computed(() => {
  const level = detailMemory.value?.fadeLevel
  if (!level || level === 'active') return ''
  return t(`memory.fade.${level}`)
})

const detailRecallLabel = computed(() => {
  const memory = detailMemory.value
  const count = Number(memory?.recallCount || 0)
  if (!count) return ''
  const when = memory?.lastRecalledAt ? formatSmartTime(memory.lastRecalledAt, { t, locale: locale.value }) : ''
  return when
    ? t('memory.recalledWithTime', { count, time: when })
    : t('memory.recalledTimes', { count })
})

const visibleStates = computed(() =>
  filterCharId.value == null
    ? states.value
    : states.value.filter((item) => String(item.characterId) === String(filterCharId.value))
)

const visibleDiaries = computed(() =>
  filterCharId.value == null
    ? diaries.value
    : diaries.value.filter((item) => String(item.characterId) === String(filterCharId.value))
)

const visibleEpisodes = computed(() =>
  filterCharId.value == null
    ? episodes.value
    : episodes.value.filter((item) => String(item.characterId) === String(filterCharId.value))
)

const groupedMemories = computed(() => {
  const groups = new Map()
  for (const mem of memories.value) {
    const charId = mem.characterId
    if (!groups.has(charId)) {
      const char = charactersStore.list.find((c) => c.id === charId)
      groups.set(charId, {
        charId,
        charName: mem.characterName || char?.name || t('memory.roleIndex', { id: charId }),
        items: []
      })
    }
    groups.get(charId).items.push(mem)
  }
  return Array.from(groups.values())
})

function readView() {
  try {
    const saved = localStorage.getItem(VIEW_STORAGE_KEY)
    return VIEWS.includes(saved) ? saved : 'brain'
  } catch {
    return 'brain'
  }
}

function setView(next) {
  view.value = next
  try {
    localStorage.setItem(VIEW_STORAGE_KEY, next)
  } catch {
    /* 隐私模式下写不了 localStorage，视图只在本次会话生效 */
  }
}

onMounted(async () => {
  await charactersStore.fetchList().catch(() => [])
  fetchAll()
})

let firstActivation = true
onActivated(() => {
  if (firstActivation) {
    firstActivation = false
    return
  }
  fetchAll()
})

/**
 * 记忆 / 情绪态 / 日记 一次性拉齐：星图要同时用到三者，
 * 分开请求会让画面先画出「只有记忆」的图再跳一次。
 */
async function fetchAll() {
  loading.value = true
  try {
    const [list, stateList, diaryList, episodeList] = await Promise.all([
      listMemories(filterCharId.value || undefined).catch(() => []),
      listCharacterStates({ silent: true }).catch(() => []),
      listAllDiaries({ page: 1, size: 30 }, { silent: true }).catch(() => []),
      listEpisodes(filterCharId.value || undefined).catch(() => [])
    ])
    memories.value = Array.isArray(list) ? list : []
    states.value = Array.isArray(stateList) ? stateList : []
    diaries.value = Array.isArray(diaryList) ? diaryList : []
    episodes.value = Array.isArray(episodeList) ? episodeList : []
  } finally {
    loading.value = false
  }
}

function formatDate(value) {
  return formatSmartTime(value, { t, locale: locale.value })
}

function characterName(mem) {
  if (!mem) return ''
  return (
    mem.characterName ||
    charactersStore.list.find((c) => String(c.id) === String(mem.characterId))?.name ||
    t('memory.roleIndex', { id: mem.characterId })
  )
}

function sectorOf(mem) {
  const id = mem?.memoryType
  return MEMORY_SECTORS.find((sector) => sector.id === id) || null
}

function sectorColor(mem) {
  return sectorOf(mem)?.color || 'var(--ly-accent, #f4a6b5)'
}

function sectorLabel(mem) {
  const sector = sectorOf(mem)
  return sector ? t(sector.nameKey) : t('memory.sectorUnknown')
}

function importancePercent(mem) {
  const value = Number(mem?.importance)
  if (!Number.isFinite(value)) return 0
  return Math.round(Math.min(1, Math.max(0, value)) * 100)
}

async function toggleSources(memId) {
  if (expandedId.value === memId) {
    expandedId.value = null
    sourceMessages.value = []
    return
  }
  expandedId.value = memId
  sourceLoading.value = true
  try {
    const detail = await getMemory(memId)
    sourceMessages.value = detail?.sourceMessages || []
  } catch {
    sourceMessages.value = []
  } finally {
    sourceLoading.value = false
  }
}

async function openDetail(mem) {
  if (!mem) return
  // 星图 emit 的是光点节点（id 形如 mem-123），先把节点翻译回记忆的形状，
  // 否则抽屉会先闪一下「未知类型 / 空摘要」再被接口结果覆盖。
  const nodeLike = mem.memoryId != null
  const id = nodeLike ? mem.memoryId : mem.id
  detailVisible.value = true
  // 统一用记忆 id：星图靠它高亮选中光点，删除也靠它
  detailId.value = id ?? null
  detailMemory.value = nodeLike
    ? {
        id,
        summary: mem.title,
        characterId: mem.characterId,
        characterName: mem.characterName,
        memoryType: mem.sector,
        importance: mem.importance,
        sourceMsgIds: [],
        createdAt: mem.createdAt
      }
    : mem
  detailSources.value = []
  if (id == null) return

  detailLoading.value = true
  try {
    const detail = await getMemory(id)
    if (detail) {
      detailMemory.value = { ...detailMemory.value, ...detail }
      detailSources.value = detail.sourceMessages || []
    }
  } catch {
    detailSources.value = []
  } finally {
    detailLoading.value = false
  }
}

function closeDetail() {
  detailVisible.value = false
  detailId.value = null
  detailMemory.value = null
  detailSources.value = []
}

async function togglePin(mem) {
  if (!mem || pinLoading.value) return
  const targetId = mem.memoryId ?? mem.id
  if (targetId == null) return
  const next = !mem.pinned
  pinLoading.value = true
  try {
    await setMemoryPinned(targetId, next)
  } catch {
    pinLoading.value = false
    return
  }
  pinLoading.value = false

  // 钉住 = 免衰减：本地先按同口径改，避免等一次刷新才亮
  const patch = next
    ? { pinned: true, strength: 1, fadeLevel: 'active', status: 'active' }
    : { pinned: false }
  memories.value = memories.value.map((item) =>
    String(item.id) === String(targetId) ? { ...item, ...patch } : item
  )
  if (detailMemory.value) {
    detailMemory.value = { ...detailMemory.value, ...patch }
  }
  ElMessage.success(next ? t('memory.pinOn') : t('memory.pinOff'))
}

async function toggleMute(mem) {
  if (!mem || muteLoading.value) return
  const targetId = mem.memoryId ?? mem.id
  if (targetId == null) return
  const next = !mem.muted
  muteLoading.value = true
  try {
    await setMemoryMuted(targetId, next)
  } catch {
    muteLoading.value = false
    return
  }
  muteLoading.value = false

  // 只改"要不要主动提"，不动强度：她仍记得，只是不再先开口
  const patch = { muted: next }
  memories.value = memories.value.map((item) =>
    String(item.id) === String(targetId) ? { ...item, ...patch } : item
  )
  if (detailMemory.value) {
    detailMemory.value = { ...detailMemory.value, ...patch }
  }
  ElMessage.success(next ? t('memory.muteOn') : t('memory.muteOff'))
}

async function removeMemory(mem) {
  if (!mem) return
  try {
    await ElMessageBox.confirm(t('memory.deleteConfirmMessage'), t('memory.deleteConfirmTitle'), {
      type: 'warning',
      confirmButtonText: t('common.delete'),
      cancelButtonText: t('common.cancel')
    })
  } catch {
    return
  }

  // 从星图点进来的是光点节点，真正的记忆 id 在 memoryId 上
  const targetId = mem.memoryId ?? mem.id
  if (targetId == null) return

  try {
    await deleteMemory(targetId)
  } catch {
    return
  }

  memories.value = memories.value.filter((item) => String(item.id) !== String(targetId))
  if (String(expandedId.value) === String(targetId)) {
    expandedId.value = null
    sourceMessages.value = []
  }
  if (String(detailId.value) === String(targetId) || String(detailId.value) === String(mem.id)) closeDetail()
  ElMessage.success(t('memory.deleted'))
}
</script>

<style lang="scss" scoped>
.memory-page {
  max-width: $narrow-page-max;

  &.is-brain {
    max-width: min(1180px, 100%);
  }
}

.page-header {
  margin-bottom: $space-6;
  animation: fadeSlideUp 0.5s ease both;
}

.page-title {
  font-size: $font-size-2xl;
  font-weight: $font-weight-bold;
  color: $color-text-primary;
  margin-bottom: $space-2;
}

.page-desc {
  font-size: $font-size-sm;
  color: $color-text-muted;
}

.filter-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: $space-3;
  margin-bottom: $space-6;
  animation: fadeSlideUp 0.5s 0.04s ease both;
}

.filter-select {
  width: min(200px, 55vw);
}

.view-toggle {
  display: inline-flex;
  padding: 2px;
  border-radius: $radius-pill;
  border: 1px solid rgba($color-pink-rgb, 0.12);
  background: rgba($color-pink-rgb, 0.04);
}

.view-toggle__btn {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 5px 12px;
  border: none;
  border-radius: $radius-pill;
  background: transparent;
  color: $color-text-muted;
  font-size: $font-size-xs;
  font-family: inherit;
  cursor: pointer;
  transition: color $transition-fast, background $transition-fast;

  &:hover {
    color: $color-text-secondary;
  }

  &.is-active {
    color: $color-text-primary;
    background: rgba($color-pink-rgb, 0.14);
    box-shadow: 0 0 12px rgba($color-pink-rgb, 0.12);
  }
}

.refresh-btn {
  margin-left: auto;
}

.loading-state,
.empty-state {
  text-align: center;
  padding: $space-16 $space-6;
  color: $color-text-muted;
}

.empty-icon {
  width: 80px;
  height: 80px;
  border-radius: $radius-xl;
  background: rgba($color-pink-rgb, 0.06);
  display: flex;
  align-items: center;
  justify-content: center;
  margin: 0 auto $space-6;
  color: $color-pink-primary;
}

.empty-state h3 {
  color: $color-text-primary;
  margin-bottom: $space-2;
}

.empty-state p {
  font-size: $font-size-sm;
}

.memory-groups {
  display: flex;
  flex-direction: column;
  gap: $space-6;
}

.memory-group {
  border-radius: $radius-xl;
  padding: 0;
  overflow: hidden;
  animation: fadeSlideUp 0.5s cubic-bezier(0.4, 0, 0.2, 1) both;
}

.group-header {
  padding: $space-4 $space-6;
  border-bottom: 1px solid rgba($color-pink-rgb, 0.06);
  background: rgba($color-pink-rgb, 0.03);
}

.group-char-info {
  display: flex;
  align-items: center;
  gap: $space-3;
}

.group-char-avatar {
  width: 32px;
  height: 32px;
  border-radius: 50%;
  overflow: hidden;
  background: rgba($color-pink-rgb, 0.1);
  display: flex;
  align-items: center;
  justify-content: center;
  color: $color-pink-primary;
  flex-shrink: 0;

  img {
    width: 100%;
    height: 100%;
    object-fit: cover;
  }
}

.group-char-name {
  font-size: $font-size-sm;
  font-weight: $font-weight-semibold;
  color: $color-text-primary;
}

.group-count {
  font-size: $font-size-xs;
  color: $color-text-muted;
  margin-left: auto;
}

.group-items {
  display: flex;
  flex-direction: column;
}

.memory-card {
  padding: $space-4 $space-6;
  border-bottom: 1px solid rgba($color-pink-rgb, 0.04);
  animation: fadeSlideUp 0.4s cubic-bezier(0.4, 0, 0.2, 1) both;
  transition: background $transition-fast;

  &:last-child {
    border-bottom: none;
  }

  &:hover {
    background: rgba($color-pink-rgb, 0.02);
  }
}

.mem-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: $space-2;
}

.mem-time {
  font-size: 11px;
  color: $color-text-muted;
  opacity: 0.5;
}

.mem-layer {
  margin-left: $space-2;
  padding: 0 6px;
  border-radius: $radius-sm;
  font-size: 10px;
  line-height: 16px;
  color: $color-text-secondary;
  background: rgba(255, 255, 255, 0.04);
  border: 1px solid rgba(255, 255, 255, 0.08);
}

.mem-summary {
  font-size: $font-size-sm;
  color: $color-text-primary;
  line-height: 1.7;
}

.mem-sources {
  margin-top: $space-3;
  padding-top: $space-3;
  border-top: 1px solid rgba($color-pink-rgb, 0.06);
}

.mem-source-list {
  margin-top: $space-3;
}

.mem-footer {
  margin-top: $space-2;
  display: flex;
  justify-content: flex-end;
  gap: $space-2;

  .btn-delete:hover {
    color: $color-error !important;
  }
}

/* 抽屉里的过渡由 Transition 负责，这里只放进入/离开的位移 */
.mem-sheet-mask {
  position: fixed;
  inset: 0;
  z-index: $z-overlay;
  display: flex;
  justify-content: flex-end;
  background: rgba(4, 8, 14, 0.55);
  backdrop-filter: blur(2px);
}

.mem-sheet {
  width: min(440px, 92vw);
  height: 100%;
  padding: $space-5 $space-5 $space-6;
  overflow-y: auto;
  background: rgba(16, 22, 32, 0.96);
  border-left: 1px solid rgba($color-pink-rgb, 0.14);
  box-shadow: -18px 0 48px rgba(0, 0, 0, 0.5);
  display: flex;
  flex-direction: column;
  gap: $space-3;
}

.mem-sheet__head {
  display: flex;
  align-items: center;
  gap: $space-2;
}

.mem-sheet__sector {
  font-size: 10px;
  font-weight: $font-weight-semibold;
  letter-spacing: 0.14em;
  text-transform: uppercase;
}

.mem-sheet__layer {
  padding: 1px 8px;
  border-radius: $radius-pill;
  font-size: 10px;
  color: $color-text-secondary;
  background: rgba(255, 255, 255, 0.05);
  border: 1px solid rgba(255, 255, 255, 0.1);
}

.mem-sheet__fade {
  padding: 1px 8px;
  border-radius: $radius-pill;
  font-size: 10px;
  color: $color-text-secondary;
  background: rgba($color-pink-rgb, 0.08);
  border: 1px solid rgba($color-pink-rgb, 0.24);
}

.mem-sheet__recalled {
  padding: 1px 8px;
  border-radius: $radius-pill;
  font-size: 10px;
  color: $color-text-muted;
  border: 1px solid rgba(255, 255, 255, 0.08);
}

.mem-sheet__quote {
  margin: 0;
  padding-left: $space-3;
  border-left: 2px solid rgba($color-pink-rgb, 0.32);
  font-size: $font-size-sm;
  line-height: 1.75;
  color: $color-text-secondary;

  em {
    display: block;
    margin-bottom: 2px;
    font-style: normal;
    font-size: 10px;
    letter-spacing: 0.12em;
    color: $color-text-muted;
  }
}

.mem-sheet__close {
  margin-left: auto;
  display: grid;
  place-items: center;
  width: 26px;
  height: 26px;
  border-radius: 50%;
  border: 1px solid rgba(255, 255, 255, 0.08);
  background: transparent;
  color: $color-text-secondary;
  cursor: pointer;

  &:hover {
    color: $color-pink-light;
    border-color: rgba($color-pink-rgb, 0.35);
  }
}

.mem-sheet__summary {
  margin: 0;
  font-size: $font-size-base;
  line-height: 1.8;
  color: $color-text-primary;
}

.mem-sheet__meta {
  display: flex;
  gap: $space-3;
  font-size: 11px;
  color: $color-text-muted;
}

.mem-sheet__weight {
  display: flex;
  align-items: center;
  gap: $space-2;
  font-size: 11px;
  color: $color-text-muted;

  b {
    color: $color-pink-light;
    font-variant-numeric: tabular-nums;
  }
}

.mem-sheet__bar {
  flex: 1;
  height: 4px;
  border-radius: 2px;
  background: rgba(255, 255, 255, 0.07);
  overflow: hidden;

  i {
    display: block;
    height: 100%;
    background: linear-gradient(90deg, rgba($color-pink-rgb, 0.45), $color-pink-primary);
  }
}

.mem-sheet__title {
  margin: $space-2 0 0;
  font-size: 10px;
  font-weight: $font-weight-semibold;
  letter-spacing: 0.1em;
  text-transform: uppercase;
  color: $color-text-muted;
}

.mem-sheet__foot {
  margin-top: auto;
  display: flex;
  justify-content: space-between;
  padding-top: $space-3;
  border-top: 1px solid rgba($color-pink-rgb, 0.08);
}

.mem-sheet__pin {
  color: $color-text-secondary;

  &:hover,
  &.is-pinned {
    color: $color-pink-light;
  }
}

.mem-sheet__mute {
  color: $color-text-secondary;

  &:hover,
  &.is-muted {
    color: rgba($color-pink-rgb, 0.9);
  }
}

.mem-sheet__delete {
  color: rgba($color-error, 0.85);

  &:hover {
    color: $color-error;
  }
}

.mem-sheet-enter-active,
.mem-sheet-leave-active {
  transition: opacity $transition-base;
}

.mem-sheet-enter-active .mem-sheet,
.mem-sheet-leave-active .mem-sheet {
  transition: transform $transition-base;
}

.mem-sheet-enter-from,
.mem-sheet-leave-to {
  opacity: 0;
}

.mem-sheet-enter-from .mem-sheet,
.mem-sheet-leave-to .mem-sheet {
  transform: translateX(24px);
}

/* ——— 共同经历 ——— */
.episode-list {
  display: flex;
  flex-direction: column;
  gap: $space-3;
}

.episode-card {
  padding: $space-4;
  border-radius: $radius-md;
}

.episode-head {
  display: flex;
  align-items: center;
  gap: $space-2;
  margin-bottom: $space-2;
}

.episode-time {
  font-size: $font-size-xs;
  color: $color-text-muted;
}

.episode-char {
  font-size: $font-size-xs;
  color: $color-text-secondary;
}

.episode-loop {
  margin-left: auto;
  padding: 1px 8px;
  border-radius: $radius-pill;
  background: rgba($color-pink-rgb, 0.14);
  color: $color-text-secondary;
  font-size: $font-size-xs;
}

.episode-quote {
  margin: 0 0 $space-2;
  padding-left: $space-3;
  border-left: 2px solid rgba($color-pink-rgb, 0.5);
  color: $color-text-primary;
  font-size: $font-size-sm;
  line-height: 1.6;
}

.episode-summary {
  margin: 0;
  color: $color-text-secondary;
  font-size: $font-size-sm;
  line-height: 1.6;
}

</style>
