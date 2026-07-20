<template>
  <div class="character-square-page companion-page stagger-container">
    <div class="square-page__ambient" aria-hidden="true">
      <div class="square-page__mesh" />
      <div class="square-orb square-orb--1" />
      <div class="square-orb square-orb--2" />
      <div class="square-page__grid" />
    </div>

    <div class="square-page__content">
    <header class="page-header">
      <div>
        <h1 class="page-title">{{ t('characterSquare.title') }}</h1>
        <p class="page-desc">{{ t('characterSquare.desc') }}</p>
      </div>
    </header>

    <div class="search-row stagger-item">
      <el-input
        v-model="searchQuery"
        class="search-input"
        :placeholder="t('characterSquare.searchPlaceholder')"
        clearable
        :prefix-icon="Search"
      />
    </div>

    <div v-if="allTags.length" class="tag-filter stagger-item">
      <el-check-tag
        :checked="!activeTag"
        class="filter-tag"
        @change="activeTag = ''"
      >
        {{ t('characterSquare.allTags') }}
      </el-check-tag>
      <el-check-tag
        v-for="tag in allTags"
        :key="tag"
        :checked="activeTag === tag"
        class="filter-tag"
        @change="activeTag = tag"
      >
        {{ tag }}
      </el-check-tag>
    </div>

    <div v-if="loading && templates.length === 0" class="template-grid">
      <div
        v-for="n in 8"
        :key="`sk-${n}`"
        class="template-card template-card--skeleton glass stagger-item"
        aria-hidden="true"
      >
        <div class="card-media skeleton-block" />
        <div class="card-body">
          <div class="skeleton-line skeleton-line--title" />
          <div class="skeleton-line skeleton-line--summary" />
        </div>
      </div>
    </div>

    <div v-else-if="searchNoMatch" class="empty-state glass stagger-item">
      <div class="empty-icon">
        <el-icon :size="44"><Search /></el-icon>
      </div>
      <h3>{{ t('characterSquare.searchEmptyTitle') }}</h3>
      <p>{{ t('characterSquare.searchEmptyDesc') }}</p>
    </div>

    <div v-else-if="templates.length === 0" class="empty-state glass stagger-item">
      <div class="empty-icon">
        <el-icon :size="44"><Shop /></el-icon>
      </div>
      <h3>{{ t('characterSquare.emptyTitle') }}</h3>
      <p>{{ t('characterSquare.emptyDesc') }}</p>
    </div>

    <div v-else class="template-grid">
      <div
        v-for="(item, idx) in templates"
        :key="item.id"
        class="template-card glass stagger-item"
        :class="{ 'template-card--loading': loading }"
        :style="{ animationDelay: `${idx * 0.05}s`, '--shine-delay': `${(idx % 6) * 0.65}s` }"
      >
        <span v-if="isMostLiked(item.id)" class="most-liked-badge">{{ t('characterSquare.mostLiked') }}</span>
        <span class="template-card__shine" aria-hidden="true" />
        <div class="card-media">
          <img
            v-if="avatarSrc(item) && !isAvatarBroken(item.id)"
            :src="resolveMediaUrl(avatarSrc(item))"
            class="avatar-img"
            :alt="item.name"
            :loading="idx < 8 ? 'eager' : 'lazy'"
            :fetchpriority="idx < 8 ? 'high' : 'auto'"
            decoding="async"
            @error="onAvatarError(item)"
          />
          <div v-else class="avatar-placeholder">
            <el-icon :size="28"><User /></el-icon>
          </div>
        </div>

        <div class="card-body">
          <SquareDanmakuLayer :comments="commentsByTemplateId[item.id] || []" />
          <h3 class="char-name">{{ item.name }}</h3>
          <div v-if="item.hasVoiceInteraction || (item.addCount ?? 0) > 0" class="badge-row">
            <span v-if="item.hasVoiceInteraction" class="voice-badge">{{ t('characterSquare.voiceInteraction') }}</span>
            <span v-if="(item.addCount ?? 0) > 0" class="add-count">{{ t('characterSquare.addCount', { n: item.addCount }) }}</span>
          </div>
          <p v-if="item.summary" class="char-summary">{{ item.summary }}</p>
          <div v-if="item.tags?.length" class="tag-row">
            <span v-for="tag in item.tags" :key="tag" class="meta-tag">{{ tag }}</span>
          </div>
        </div>

        <SquareCommentInput
          :template-id="item.id"
          :comments="commentsByTemplateId[item.id] || []"
          @updated="reloadComments(item.id)"
        />

        <div class="card-actions">
          <button
            type="button"
            class="like-btn"
            :class="{ 'like-btn--active': item.liked }"
            :disabled="likingId === item.id"
            @click="handleLike(item)"
          >
            <el-icon :size="16">
              <StarFilled v-if="item.liked" />
              <Star v-else />
            </el-icon>
            <span>{{ item.likeCount ?? 0 }}</span>
          </button>
          <el-button text size="small" @click="openPreview(item)">
            {{ t('characterSquare.preview') }}
          </el-button>
          <el-button
            v-if="item.added"
            type="default"
            size="small"
            disabled
          >
            {{ t('characterSquare.added') }}
          </el-button>
          <el-button
            v-else
            type="primary"
            size="small"
            class="btn-cta"
            :loading="addingId === item.id"
            @click="handleAdd(item)"
          >
            {{ t('characterSquare.add') }}
          </el-button>
          <el-button
            v-if="item.added && item.addedCharacterId"
            text
            size="small"
            :icon="ChatDotRound"
            @click="startChat(item.addedCharacterId)"
          >
            {{ t('characterSquare.chatNow') }}
          </el-button>
        </div>
      </div>
    </div>

    <div v-if="!searchNoMatch && total > PAGE_SIZE" class="square-pagination">
      <el-pagination
        background
        layout="prev, pager, next"
        :current-page="page"
        :page-size="PAGE_SIZE"
        :total="total"
        @current-change="onPageChange"
      />
    </div>
    </div>

    <el-drawer
      v-model="previewVisible"
      :title="previewItem?.name || t('characterSquare.previewTitle')"
      direction="rtl"
      size="420px"
      destroy-on-close
    >
      <div v-if="previewItem" class="preview-body">
        <div class="preview-hero">
          <img
            v-if="previewItem.avatarUrl && !isAvatarBroken(previewItem.id)"
            :src="resolveMediaUrl(previewItem.avatarUrl)"
            class="preview-avatar"
            :alt="previewItem.name"
            loading="lazy"
            decoding="async"
            @error="markAvatarBroken(previewItem.id)"
          />
          <div v-else class="preview-avatar placeholder">
            <el-icon :size="36"><User /></el-icon>
          </div>
          <p v-if="previewItem.summary" class="preview-summary">{{ previewItem.summary }}</p>
        </div>
        <h4 class="preview-label">{{ t('characterSquare.promptLabel') }}</h4>
        <pre class="preview-prompt">{{ previewItem.promptTemplate || t('characters.noPrompt') }}</pre>
        <el-button
          v-if="previewItem && !previewItem.added"
          type="primary"
          class="btn-cta preview-add-btn"
          :loading="addingId === previewItem.id"
          @click="handleAdd(previewItem)"
        >
          {{ t('characterSquare.add') }}
        </el-button>
      </div>
    </el-drawer>

    <el-dialog
      v-model="addDialogVisible"
      class="square-add-dialog"
      :title="t('characterSquare.addDialogTitle')"
      width="420px"
      destroy-on-close
      @close="cancelAddDialog"
    >
      <CharacterCityModeForm
        v-model:city-mode="addCityMode"
        v-model:city="addCity"
      />
      <template #footer>
        <el-button @click="cancelAddDialog">{{ t('common.cancel') }}</el-button>
        <el-button
          type="primary"
          class="btn-cta"
          :loading="addingId != null"
          @click="confirmAdd"
        >
          {{ t('characterSquare.confirmAdd') }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, onActivated, onUnmounted, ref, watch } from 'vue'
defineOptions({ name: 'CharacterSquarePage' })
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ChatDotRound, Search, Shop, Star, StarFilled, User } from '@element-plus/icons-vue'
import { useCharacterSquareStore } from '@/stores/characterSquare'
import { useCharactersStore } from '@/stores/characters'
import { createConversation } from '@/api/conversation'
import { getSavedUserCity, saveUserCity } from '@/utils/userCity'
import { resolveMediaUrl } from '@/utils/media'
import CharacterCityModeForm from '@/components/CharacterCityModeForm.vue'
import SquareCommentInput from '@/components/SquareCommentInput.vue'
import SquareDanmakuLayer from '@/components/SquareDanmakuLayer.vue'
import {
  fetchSquareComments,
  fetchSquareCommentsBatch,
  fetchSquareTemplateDetail,
} from '@/api/characterSquare'
import { useUserStore } from '@/stores/user'
import { normalizeSquareComments } from '@/utils/squareComment'

const { t } = useI18n()
const router = useRouter()
const squareStore = useCharacterSquareStore()
const userStore = useUserStore()

const PAGE_SIZE = 24
const FETCH_SIZE = 24

const loading = ref(true)
const templates = ref([])
const allTags = ref([])
const activeTag = ref('')
const searchQuery = ref('')
const page = ref(1)
const total = ref(0)
const addingId = ref(null)
const likingId = ref(null)
const previewVisible = ref(false)
const previewItem = ref(null)
const previewLoading = ref(false)
const addDialogVisible = ref(false)
const addCityMode = ref('real')
const addCity = ref('')
const commentsByTemplateId = ref({})
const avatarLoadTier = ref({})
let commentLoadTimer = null
let commentLoadSeq = 0
let searchDebounceTimer = null
let catalogRequestSeq = 0

function avatarSrc(item) {
  if (!item) return ''
  if (avatarLoadTier.value[item.id] === 'orig') {
    return item.avatarUrl || item.avatarThumbUrl || ''
  }
  if (avatarLoadTier.value[item.id] === 'broken') {
    return ''
  }
  return item.avatarThumbUrl || item.avatarUrl || ''
}

function isAvatarBroken(templateId) {
  return avatarLoadTier.value[templateId] === 'broken'
}

function isMostLiked(templateId) {
  return squareStore.isMostLiked(templateId, templates.value)
}

const searchNoMatch = computed(
  () => !loading.value && (searchQuery.value.trim() !== '' || activeTag.value) && templates.value.length === 0,
)

watch(activeTag, () => {
  page.value = 1
  void loadCatalog()
})

watch(searchQuery, () => {
  page.value = 1
  if (searchDebounceTimer) clearTimeout(searchDebounceTimer)
  searchDebounceTimer = setTimeout(() => {
    void loadCatalog()
  }, 280)
})

onUnmounted(() => {
  if (commentLoadTimer) {
    clearTimeout(commentLoadTimer)
    commentLoadTimer = null
  }
  if (searchDebounceTimer) {
    clearTimeout(searchDebounceTimer)
    searchDebounceTimer = null
  }
})

function markAvatarBroken(templateId) {
  if (!templateId) return
  avatarLoadTier.value = { ...avatarLoadTier.value, [templateId]: 'broken' }
}

function onAvatarError(item) {
  if (!item?.id) return
  const thumb = item.avatarThumbUrl || ''
  const orig = item.avatarUrl || ''
  if (avatarLoadTier.value[item.id] !== 'orig' && orig && orig !== thumb) {
    avatarLoadTier.value = { ...avatarLoadTier.value, [item.id]: 'orig' }
    return
  }
  markAvatarBroken(item.id)
}

function prefetchThumbUrls(items = []) {
  const run = () => {
    for (const item of items) {
      const url = avatarSrc(item)
      if (!url) continue
      const img = new Image()
      img.decoding = 'async'
      img.src = resolveMediaUrl(url)
    }
  }
  if (typeof requestIdleCallback === 'function') {
    requestIdleCallback(run, { timeout: 2000 })
  } else {
    setTimeout(run, 120)
  }
}

/** 翻页后先让浏览器拉头像，再异步加载弹幕评论，避免同域并发挤占连接。 */
function scheduleCommentsForTemplates(templateIds = []) {
  if (commentLoadTimer) {
    clearTimeout(commentLoadTimer)
  }
  const seq = ++commentLoadSeq
  commentLoadTimer = setTimeout(() => {
    if (seq !== commentLoadSeq) return
    void loadCommentsForTemplates(templateIds)
  }, 450)
}

onMounted(() => loadCatalog())

let firstActivation = true
onActivated(() => {
  if (firstActivation) { firstActivation = false; return }
  loadCatalog()
})

async function loadCatalog(force = false) {
  const seq = ++catalogRequestSeq
  loading.value = true
  try {
    const data = await squareStore.fetchTemplates({
      page: page.value,
      size: FETCH_SIZE,
      tag: activeTag.value?.trim() || '',
      keyword: searchQuery.value.trim() || '',
      force,
    })
    if (seq !== catalogRequestSeq) return
    templates.value = data?.records || []
    allTags.value = data?.tags || []
    total.value = data?.total ?? templates.value.length
    scheduleCommentsForTemplates(templates.value.map(item => item.id))
    prefetchNextPageThumbs()
  } finally {
    if (seq === catalogRequestSeq) {
      loading.value = false
    }
  }
}

function prefetchNextPageThumbs() {
  if (page.value * PAGE_SIZE >= total.value) return
  void squareStore.fetchTemplates({
    page: page.value + 1,
    size: FETCH_SIZE,
    tag: activeTag.value?.trim() || '',
    keyword: searchQuery.value.trim() || '',
  }).then((data) => {
    prefetchThumbUrls(data?.records || [])
  }).catch(() => {})
}

function onPageChange(nextPage) {
  page.value = nextPage
  void loadCatalog()
}

async function loadCommentsForTemplates(templateIds = []) {
  const ids = [...new Set(templateIds.filter(Boolean))]
  if (!ids.length) return
  try {
    const batch = await fetchSquareCommentsBatch(ids)
    const next = { ...commentsByTemplateId.value }
    for (const templateId of ids) {
      next[templateId] = normalizeSquareComments(batch?.[templateId] || batch?.[String(templateId)] || [], userStore.userId)
    }
    commentsByTemplateId.value = next
  } catch {
    /* interceptor */
  }
}

async function reloadComments(templateId) {
  if (!templateId) return
  try {
    const list = await fetchSquareComments(templateId)
    commentsByTemplateId.value = {
      ...commentsByTemplateId.value,
      [templateId]: normalizeSquareComments(list, userStore.userId),
    }
  } catch {
    /* interceptor */
  }
}

async function openPreview(item) {
  previewItem.value = { ...item }
  previewVisible.value = true
  previewLoading.value = true
  try {
    const detail = await fetchSquareTemplateDetail(item.id)
    previewItem.value = { ...item, ...detail, avatarUrl: detail?.avatarUrl || item.avatarUrl }
  } catch {
    /* keep card data */
  } finally {
    previewLoading.value = false
  }
}

/** @type {((value: object | null) => void) | null} */
let addDialogResolve = null

async function openAddCityDialog() {
  addCityMode.value = 'real'
  addCity.value = getSavedUserCity()
  addDialogVisible.value = true
  return new Promise((resolve) => {
    addDialogResolve = resolve
  })
}

function cancelAddDialog() {
  addDialogVisible.value = false
  addDialogResolve?.(null)
  addDialogResolve = null
}

async function confirmAdd() {
  if (addCityMode.value === 'real' && !addCity.value?.trim()) {
    ElMessage.warning(t('characterSquare.cityRequired'))
    return
  }
  const payload = {
    cityMode: addCityMode.value,
    city: addCityMode.value === 'real' ? addCity.value.trim() : undefined
  }
  if (payload.city) saveUserCity(payload.city)
  addDialogVisible.value = false
  addDialogResolve?.(payload)
  addDialogResolve = null
}

async function handleAdd(item) {
  if (!item || item.added || addingId.value != null) return
  const cityPayload = await openAddCityDialog()
  if (!cityPayload) return
  addingId.value = item.id
  try {
    const created = await squareStore.addTemplate(item.id, cityPayload)
    if (created) {
      useCharactersStore().upsertLocal(created)
    }
    squareStore.markAddedInCache(item.id, created?.id)
    markAddedLocal(item.id, created?.id)
    item.added = true
    item.addedCharacterId = created?.id ?? null
    ElMessage.success(t('characterSquare.addSuccess'))
    try {
      await ElMessageBox.confirm(
        t('characterSquare.addSuccessHint'),
        t('characterSquare.addSuccess'),
        {
          confirmButtonText: t('characterSquare.goCharacters'),
          cancelButtonText: t('characterSquare.chatNow'),
          distinguishCancelAndClose: true,
          type: 'success'
        }
      )
      router.push('/app/characters')
    } catch (action) {
      if (action === 'cancel' && item.addedCharacterId) {
        await startChat(item.addedCharacterId)
      }
    }
  } catch (err) {
    const msg = err?.message || ''
    if (msg.includes(t('characterSquare.alreadyAdded')) || msg.includes('已经添加过')) {
      item.added = true
      await loadCatalog()
    }
  } finally {
    addingId.value = null
  }
}

function markAddedLocal(templateId, characterId) {
  templates.value = templates.value.map(item =>
    item.id === templateId
      ? { ...item, added: true, addedCharacterId: characterId ?? item.addedCharacterId }
      : item,
  )
}

async function handleLike(item) {
  if (!item || likingId.value != null) return
  likingId.value = item.id
  try {
    const result = await squareStore.toggleLike(item.id)
    item.liked = !!result?.liked
    item.likeCount = result?.likeCount ?? item.likeCount ?? 0
    templates.value = templates.value.map(row =>
      row.id === item.id
        ? { ...row, liked: item.liked, likeCount: item.likeCount }
        : row,
    )
  } finally {
    likingId.value = null
  }
}

async function startChat(characterId) {
  if (!characterId) return
  try {
    const conv = await createConversation({ characterId, mode: 'SINGLE' })
    if (conv?.id) {
      // Suppress meet/proactive toast if the cold-open notification races with navigation.
      const { setActiveChatConversationId } = await import('@/composables/useActiveChatContext')
      setActiveChatConversationId(conv.id)
      router.push(`/app/chat/${conv.id}`)
    }
  } catch {
    /* interceptor shows error */
  }
}
</script>

<style lang="scss" scoped>
.character-square-page {
  position: relative;
  max-width: $max-content-width;
  margin: 0 auto;
  padding: $space-6 $space-8 $space-12;
  overflow: hidden;
}

.square-page__ambient {
  position: absolute;
  inset: -$space-8 -$space-4;
  z-index: 0;
  pointer-events: none;
  overflow: hidden;
}

.square-page__mesh {
  position: absolute;
  inset: 0;
  background:
    radial-gradient(ellipse 65% 50% at 8% 0%, rgba($color-pink-rgb, 0.16), transparent 55%),
    radial-gradient(ellipse 50% 40% at 92% 20%, rgba(100, 90, 200, 0.1), transparent 50%),
    radial-gradient(ellipse 45% 35% at 50% 100%, rgba($color-pink-rgb, 0.06), transparent 48%);
}

.square-page__grid {
  position: absolute;
  inset: 0;
  opacity: 0.035;
  background-image:
    linear-gradient(rgba(255, 255, 255, 0.06) 1px, transparent 1px),
    linear-gradient(90deg, rgba(255, 255, 255, 0.06) 1px, transparent 1px);
  background-size: 40px 40px;
  mask-image: radial-gradient(ellipse 80% 70% at 50% 30%, black, transparent);
}

.square-orb {
  position: absolute;
  border-radius: 50%;
  filter: blur(64px);
  animation: squareOrbFloat 12s ease-in-out infinite alternate;
}

.square-orb--1 {
  width: min(200px, 40vw);
  height: min(200px, 40vw);
  top: -4%;
  right: 6%;
  background: rgba($color-pink-rgb, 0.2);
}

.square-orb--2 {
  width: min(160px, 32vw);
  height: min(160px, 32vw);
  bottom: 8%;
  left: -4%;
  background: rgba(120, 100, 200, 0.12);
  animation-delay: -4s;
}

@keyframes squareOrbFloat {
  from { transform: translate(0, 0); }
  to { transform: translate(12px, -16px); }
}

.square-page__content {
  position: relative;
  z-index: 1;
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

.search-row {
  margin-bottom: $space-5;
}

.search-input {
  max-width: 360px;

  :deep(.el-input__wrapper) {
    border-radius: $radius-pill;
    background: rgba($color-pink-rgb, 0.04);
    box-shadow: none;
    border: 1px solid rgba($color-pink-rgb, 0.12);
  }

  :deep(.el-input__wrapper.is-focus) {
    border-color: rgba($color-pink-rgb, 0.35);
    box-shadow: 0 0 0 3px rgba($color-pink-rgb, 0.12);
  }
}

.tag-filter {
  display: flex;
  flex-wrap: wrap;
  gap: $space-2;
  margin-bottom: $space-6;
}

.filter-tag {
  cursor: pointer;
}

.template-card--skeleton {
  pointer-events: none;
}

.skeleton-block {
  width: 100%;
  aspect-ratio: 1;
  border-radius: $radius-lg;
  background: linear-gradient(
    90deg,
    rgba($color-pink-rgb, 0.06) 25%,
    rgba($color-pink-rgb, 0.12) 50%,
    rgba($color-pink-rgb, 0.06) 75%
  );
  background-size: 200% 100%;
  animation: square-skeleton-shimmer 1.2s ease-in-out infinite;
}

.skeleton-line {
  height: 12px;
  border-radius: $radius-full;
  margin-bottom: $space-2;
  background: rgba($color-pink-rgb, 0.08);
}

.skeleton-line--title {
  width: 55%;
  height: 16px;
}

.skeleton-line--summary {
  width: 85%;
}

@keyframes square-skeleton-shimmer {
  0% { background-position: 200% 0; }
  100% { background-position: -200% 0; }
}

.loading-state,
.empty-state {
  text-align: center;
  padding: $space-16 $space-8;
  color: $color-text-muted;
}

.empty-state {
  max-width: 420px;
  margin: $space-8 auto 0;
  border-radius: $radius-xl;
}

.empty-icon {
  width: 88px;
  height: 88px;
  border-radius: $radius-xl;
  background: linear-gradient(145deg, rgba($color-pink-rgb, 0.14), rgba($color-pink-rgb, 0.04));
  border: 1px solid rgba($color-pink-rgb, 0.15);
  display: flex;
  align-items: center;
  justify-content: center;
  margin: 0 auto $space-6;
  color: $color-pink-primary;
}

.empty-state h3 {
  color: $color-text-primary;
  font-size: $font-size-xl;
  margin-bottom: $space-3;
}

// 侧栏布局下：宽屏 4 列、中屏 3 列、平板 2 列、手机 1 列（约 28 个模板时更饱满）
.template-grid {
  display: grid;
  gap: $space-4;
  grid-template-columns: minmax(0, 1fr);

  @media (min-width: 560px) {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  @media (min-width: 992px) {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }

  @media (min-width: 1280px) {
    grid-template-columns: repeat(4, minmax(0, 1fr));
  }
}

.template-card {
  position: relative;
  overflow: hidden;
  border-radius: $radius-lg;
  padding: $space-4 $space-4 $space-5;
  display: flex;
  flex-direction: column;
  align-items: stretch;
  gap: 0;
  min-height: 100%;
  animation: fadeSlideUp 0.5s cubic-bezier(0.4, 0, 0.2, 1) both;
  transition: border-color $transition-fast, box-shadow $transition-fast, transform $transition-fast;

  &:hover {
    border-color: rgba($color-pink-rgb, 0.12);
    box-shadow: $shadow-glow-pink;
    transform: translateY(-2px);
  }
}

.template-card__shine {
  position: absolute;
  top: 0;
  left: -100%;
  width: 55%;
  height: 100%;
  z-index: 2;
  pointer-events: none;
  background: linear-gradient(
    105deg,
    transparent 0%,
    rgba(255, 255, 255, 0.06) 35%,
    rgba(255, 255, 255, 0.22) 50%,
    rgba(255, 255, 255, 0.06) 65%,
    transparent 100%
  );
  animation: cardShineSweep 5.5s ease-in-out infinite;
  animation-delay: var(--shine-delay, 0s);
}

@keyframes cardShineSweep {
  0%,
  72%,
  100% {
    left: -100%;
  }
  88% {
    left: 130%;
  }
}

@media (prefers-reduced-motion: reduce) {
  .square-orb {
    animation: none;
  }

  .template-card__shine {
    display: none;
  }
}

.card-media {
  flex-shrink: 0;
  width: 100%;
  aspect-ratio: 1;
  max-height: 148px;
  margin: 0 auto $space-3;
  border-radius: $radius-lg;
  overflow: hidden;
}

.avatar-img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.avatar-placeholder {
  width: 100%;
  height: 100%;
  background: rgba($color-pink-rgb, 0.08);
  display: flex;
  align-items: center;
  justify-content: center;
  color: $color-pink-primary;
}

.card-body {
  position: relative;
  flex: 1;
  min-width: 0;
  text-align: center;
  padding-bottom: 46px;
  overflow: hidden;
}

.char-name {
  font-size: $font-size-base;
  font-weight: $font-weight-semibold;
  color: $color-text-primary;
  margin-bottom: $space-2;
}

.badge-row {
  display: flex;
  flex-wrap: wrap;
  gap: $space-2;
  justify-content: center;
  margin-bottom: $space-2;
}

.voice-badge {
  font-size: $font-size-xs;
  padding: 2px 8px;
  border-radius: $radius-full;
  background: rgba($color-pink-rgb, 0.14);
  color: $color-pink-primary;
  border: 1px solid rgba($color-pink-rgb, 0.22);
  font-weight: $font-weight-semibold;
}

.add-count {
  font-size: $font-size-xs;
  color: $color-text-secondary;
}

.char-summary {
  font-size: $font-size-xs;
  color: $color-text-secondary;
  line-height: $line-height-relaxed;
  margin-bottom: $space-3;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.tag-row {
  display: flex;
  flex-wrap: wrap;
  gap: $space-2;
  justify-content: center;
}

.meta-tag {
  font-size: $font-size-xs;
  padding: 2px 8px;
  border-radius: $radius-full;
  background: rgba($color-pink-rgb, 0.08);
  color: $color-pink-primary;
  border: 1px solid rgba($color-pink-rgb, 0.12);
}

.most-liked-badge {
  position: absolute;
  top: $space-3;
  left: $space-3;
  z-index: 3;
  max-width: calc(100% - #{$space-6});
  padding: 4px 10px;
  border-radius: $radius-full;
  font-size: $font-size-xs;
  font-weight: $font-weight-semibold;
  color: #fff;
  background: linear-gradient(135deg, rgba($color-pink-rgb, 0.95), rgba(120, 90, 200, 0.92));
  box-shadow: 0 4px 14px rgba($color-pink-rgb, 0.25);
}

.like-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 4px 10px;
  border-radius: $radius-full;
  border: 1px solid rgba($color-pink-rgb, 0.16);
  background: rgba($color-pink-rgb, 0.05);
  color: $color-text-secondary;
  cursor: pointer;
  transition: color $transition-fast, border-color $transition-fast, background $transition-fast;

  &:hover:not(:disabled) {
    color: $color-pink-primary;
    border-color: rgba($color-pink-rgb, 0.28);
    background: rgba($color-pink-rgb, 0.1);
  }

  &:disabled {
    opacity: 0.6;
    cursor: not-allowed;
  }
}

.like-btn--active {
  color: $color-pink-primary;
  border-color: rgba($color-pink-rgb, 0.35);
  background: rgba($color-pink-rgb, 0.14);
}

.card-actions {
  width: 100%;
  display: flex;
  flex-wrap: wrap;
  gap: $space-1 $space-2;
  align-items: center;
  justify-content: center;
  margin-top: auto;
  padding-top: $space-3;
  border-top: 1px solid rgba($color-pink-rgb, 0.06);

  :deep(.el-button) {
    margin: 0;
  }

  :deep(.el-button--small) {
    padding: 4px 10px;
  }
}

.preview-body {
  padding: 0 $space-2 $space-6;
}

.preview-hero {
  text-align: center;
  margin-bottom: $space-6;
}

.preview-avatar {
  width: 96px;
  height: 96px;
  border-radius: $radius-xl;
  object-fit: cover;
  margin: 0 auto $space-4;
  display: block;

  &.placeholder {
    background: rgba($color-pink-rgb, 0.08);
    display: flex;
    align-items: center;
    justify-content: center;
    color: $color-pink-primary;
  }
}

.preview-summary {
  color: $color-text-secondary;
  font-size: $font-size-sm;
  line-height: $line-height-relaxed;
}

.preview-label {
  font-size: $font-size-sm;
  color: $color-text-muted;
  margin-bottom: $space-3;
}

.preview-prompt {
  white-space: pre-wrap;
  word-break: break-word;
  font-family: inherit;
  font-size: $font-size-sm;
  line-height: $line-height-relaxed;
  color: $color-text-secondary;
  background: rgba($color-pink-rgb, 0.04);
  border: 1px solid rgba($color-pink-rgb, 0.08);
  border-radius: $radius-md;
  padding: $space-4;
  max-height: 50vh;
  overflow: auto;
  margin-bottom: $space-6;
}

.preview-add-btn {
  width: 100%;
}

.square-pagination {
  display: flex;
  justify-content: center;
  margin-top: $space-8;
  padding-bottom: $space-4;
}
</style>
