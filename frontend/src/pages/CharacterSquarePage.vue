<template>
  <div class="character-square-page stagger-container">
    <header class="page-header">
      <div>
        <h1 class="page-title">{{ t('characterSquare.title') }}</h1>
        <p class="page-desc">{{ t('characterSquare.desc') }}</p>
      </div>
    </header>

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

    <div v-if="loading" class="loading-state">
      <el-icon class="is-loading" :size="24"><Loading /></el-icon>
      <span>{{ t('common.loading') }}</span>
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
        :style="{ animationDelay: `${idx * 0.05}s` }"
      >
        <div class="card-media">
          <img v-if="item.avatarUrl" :src="item.avatarUrl" class="avatar-img" :alt="item.name" />
          <div v-else class="avatar-placeholder">
            <el-icon :size="28"><User /></el-icon>
          </div>
        </div>

        <div class="card-body">
          <h3 class="char-name">{{ item.name }}</h3>
          <p v-if="item.summary" class="char-summary">{{ item.summary }}</p>
          <div v-if="item.tags?.length" class="tag-row">
            <span v-for="tag in item.tags" :key="tag" class="meta-tag">{{ tag }}</span>
          </div>
        </div>

        <div class="card-actions">
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

    <div v-if="!loading && total > PAGE_SIZE" class="square-pagination">
      <el-pagination
        background
        layout="prev, pager, next"
        :current-page="page"
        :page-size="PAGE_SIZE"
        :total="total"
        @current-change="onPageChange"
      />
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
          <img v-if="previewItem.avatarUrl" :src="previewItem.avatarUrl" class="preview-avatar" :alt="previewItem.name" />
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
  </div>
</template>

<script setup>
import { onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ChatDotRound, Loading, Shop, User } from '@element-plus/icons-vue'
import { addCharacterFromSquare, listCharacterSquareTemplates } from '@/api/characterSquare'
import { createConversation } from '@/api/conversation'

const { t } = useI18n()
const router = useRouter()

const PAGE_SIZE = 12

const loading = ref(true)
const templates = ref([])
const allTags = ref([])
const activeTag = ref('')
const page = ref(1)
const total = ref(0)
const addingId = ref(null)
const previewVisible = ref(false)
const previewItem = ref(null)

watch(activeTag, () => {
  page.value = 1
  loadTemplates()
})

onMounted(loadTemplates)

async function loadTemplates() {
  loading.value = true
  try {
    const data = await listCharacterSquareTemplates({
      page: page.value,
      size: PAGE_SIZE,
      tag: activeTag.value || undefined
    })
    templates.value = data?.records || []
    total.value = data?.total ?? 0
    allTags.value = data?.tags || []
    if (data?.page) {
      page.value = data.page
    }
  } finally {
    loading.value = false
  }
}

function onPageChange(nextPage) {
  page.value = nextPage
  loadTemplates()
}

function openPreview(item) {
  previewItem.value = item
  previewVisible.value = true
}

async function handleAdd(item) {
  if (!item || item.added || addingId.value != null) return
  addingId.value = item.id
  try {
    const created = await addCharacterFromSquare(item.id)
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
      await loadTemplates()
    }
  } finally {
    addingId.value = null
  }
}

async function startChat(characterId) {
  if (!characterId) return
  try {
    const conv = await createConversation({ characterId, mode: 'SINGLE' })
    if (conv?.id) {
      router.push(`/app/chat/${conv.id}`)
    }
  } catch {
    /* interceptor shows error */
  }
}
</script>

<style lang="scss" scoped>
.character-square-page {
  max-width: $max-content-width;
  margin: 0 auto;
  padding: $space-6 $space-8 $space-12;
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

.tag-filter {
  display: flex;
  flex-wrap: wrap;
  gap: $space-2;
  margin-bottom: $space-6;
}

.filter-tag {
  cursor: pointer;
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
  flex: 1;
  min-width: 0;
  text-align: center;
}

.char-name {
  font-size: $font-size-base;
  font-weight: $font-weight-semibold;
  color: $color-text-primary;
  margin-bottom: $space-2;
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
