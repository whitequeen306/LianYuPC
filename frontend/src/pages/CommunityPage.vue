<template>
  <div class="community-page companion-page companion-feed stagger-container">
    <header class="companion-hero stagger-item">
      <span class="companion-eyebrow">Community</span>
      <h1 class="companion-title">{{ t('community.title') }}</h1>
      <p class="companion-lead">{{ t('community.desc') }}</p>
    </header>

    <section v-tilt class="feed-compose-card glass stagger-item">
      <span class="feed-compose-card__label">分享此刻</span>
      <el-input
        v-model="draft"
        type="textarea"
        :rows="3"
        :autosize="{ minRows: 3, maxRows: 6 }"
        :placeholder="t('community.composePlaceholder')"
        maxlength="1000"
        show-word-limit
        resize="none"
      />
      <div v-if="pendingImages.length" class="compose-images">
        <div v-for="(img, idx) in pendingImages" :key="img" class="compose-images__item">
          <img :src="resolveMediaUrl(img)" alt="" />
          <button type="button" class="compose-images__remove" @click="pendingImages.splice(idx, 1)">×</button>
        </div>
      </div>
      <div v-if="shareSnapshotLoading" class="compose-share-loading">
        正在生成对话截图…
      </div>
      <div class="compose-character">
        <el-select
          v-model="linkedCharacterId"
          clearable
          filterable
          placeholder="关联角色（可选）"
          class="compose-character__select"
        >
          <el-option
            v-for="c in characterOptions"
            :key="c.id"
            :label="c.name"
            :value="c.id"
          />
        </el-select>
        <div v-if="selectedCharacter" class="compose-character__chip">
          <span class="compose-character__chip-avatar">
            <img
              v-if="selectedCharacterAvatarSrc && !selectedCharacterAvatarBroken"
              :src="selectedCharacterAvatarSrc"
              alt=""
              @error="onSelectedCharacterAvatarError"
            />
            <el-icon v-else :size="14"><User /></el-icon>
          </span>
          <span>{{ selectedCharacter.name }}</span>
        </div>
      </div>
      <div class="compose-actions">
        <el-button :loading="uploading" @click="triggerPick">上传图片</el-button>
        <el-button
          v-bubble-btn
          type="primary"
          :loading="sending || shareSnapshotLoading"
          :disabled="!canPublish || shareSnapshotLoading"
          @click="publish"
        >
          {{ t('community.publish') }}
        </el-button>
        <input ref="fileInput" type="file" accept="image/jpeg,image/png,image/webp,image/gif" hidden @change="onPick" />
      </div>
    </section>

    <div v-if="loading && posts.length === 0" class="feed-empty glass stagger-item">加载中…</div>
    <div v-else-if="!posts.length" class="feed-empty glass stagger-item">{{ t('community.empty') }}</div>

    <div v-else class="community-feed">
      <header class="community-feed__head stagger-item">
        <span class="community-feed__eyebrow">最新动态</span>
      </header>
      <div class="community-feed__stack">
        <CommunityPostCard
          v-for="post in posts"
          :key="post.id"
          :post="post"
          :show-comments="openCommentsId === post.id"
          allow-delete
          @like="onLike"
          @toggle-comments="toggleComments"
          @delete="onDelete"
        />
      </div>
    </div>

    <div v-if="hasMore" class="load-more">
      <el-button text type="primary" :loading="loadingMore" @click="loadMore">加载更多</el-button>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
import { User } from '@element-plus/icons-vue'
import CommunityPostCard from '@/components/community/CommunityPostCard.vue'
import {
  createCommunityPost,
  deleteCommunityPost,
  fetchCommunityFeed,
  toggleCommunityLike,
  uploadCommunityImage
} from '@/api/community'
import { resolveMediaUrl } from '@/utils/media'
import {
  consumeCommunityShareDraft,
  COMMUNITY_SHARE_DRAFT_KIND_CHAT
} from '@/utils/communityShareDraft'
import { renderChatShareSnapshot } from '@/utils/renderChatShareSnapshot'
import {
  nextCharacterAvatarTier,
  resolveCharacterAvatarSrc
} from '@/utils/characterAvatar'
import { useCharactersStore } from '@/stores/characters'

const { t } = useI18n()
const charactersStore = useCharactersStore()

const posts = ref([])
const cursor = ref(null)
const hasMore = ref(false)
const loading = ref(false)
const loadingMore = ref(false)
const draft = ref('')
const pendingImages = ref([])
const linkedCharacterId = ref(null)
const uploading = ref(false)
const sending = ref(false)
const shareSnapshotLoading = ref(false)
const openCommentsId = ref(null)
const fileInput = ref(null)
const selectedCharacterAvatarTier = ref('thumb')
const selectedCharacterAvatarBroken = ref(false)

const characterOptions = computed(() => charactersStore.list || [])
const selectedCharacter = computed(() =>
  characterOptions.value.find((c) => c.id === linkedCharacterId.value) || null
)
const selectedCharacterAvatarSrc = computed(() => {
  const raw = resolveCharacterAvatarSrc({
    character: selectedCharacter.value,
    tier: selectedCharacterAvatarTier.value
  })
  return raw ? resolveMediaUrl(raw) : ''
})

const canPublish = computed(() => draft.value.trim() || pendingImages.value.length)

onMounted(async () => {
  await charactersStore.fetchList()
  await applyShareDraft()
  reload()
})

watch(linkedCharacterId, () => {
  selectedCharacterAvatarTier.value = 'thumb'
  selectedCharacterAvatarBroken.value = false
})

function onSelectedCharacterAvatarError() {
  const character = selectedCharacter.value
  if (!character) {
    selectedCharacterAvatarBroken.value = true
    return
  }
  const nextTier = nextCharacterAvatarTier(character, selectedCharacterAvatarTier.value)
  if (nextTier === 'broken') {
    selectedCharacterAvatarBroken.value = true
    return
  }
  selectedCharacterAvatarTier.value = nextTier
}

async function applyShareDraft() {
  const shareDraft = consumeCommunityShareDraft()
  if (!shareDraft) return

  if (shareDraft.linkedCharacterId) {
    linkedCharacterId.value = shareDraft.linkedCharacterId
  }

  if (shareDraft.kind === COMMUNITY_SHARE_DRAFT_KIND_CHAT && shareDraft.messages?.length) {
    shareSnapshotLoading.value = true
    try {
      const blob = await renderChatShareSnapshot(shareDraft)
      const file = new File([blob], `chat-share-${Date.now()}.png`, { type: 'image/png' })
      const res = await uploadCommunityImage(file)
      if (res?.imageUrl) {
        pendingImages.value = [res.imageUrl]
        draft.value = ''
      } else {
        throw new Error('upload failed')
      }
    } catch (e) {
      if (shareDraft.fallbackContent) {
        draft.value = shareDraft.fallbackContent
      }
      ElMessage.warning(e?.message || '对话截图生成失败，已改为文字分享')
    } finally {
      shareSnapshotLoading.value = false
    }
    return
  }

  if (shareDraft.content) {
    draft.value = shareDraft.content
  }
}

async function reload() {
  loading.value = true
  cursor.value = null
  try {
    const data = await fetchCommunityFeed({ limit: 20 })
    posts.value = data?.items || []
    cursor.value = data?.nextCursor || null
    hasMore.value = !!data?.hasMore
  } catch (e) {
    ElMessage.error(e?.message || '加载失败')
  } finally {
    loading.value = false
  }
}

async function loadMore() {
  if (!hasMore.value || loadingMore.value) return
  loadingMore.value = true
  try {
    const data = await fetchCommunityFeed({ cursor: cursor.value, limit: 20 })
    const batch = data?.items || []
    posts.value = [...posts.value, ...batch]
    cursor.value = data?.nextCursor || null
    hasMore.value = !!data?.hasMore
  } finally {
    loadingMore.value = false
  }
}

function triggerPick() {
  fileInput.value?.click()
}

async function onPick(event) {
  const file = event.target.files?.[0]
  event.target.value = ''
  if (!file) return
  if (pendingImages.value.length >= 9) {
    ElMessage.warning('最多 9 张图片')
    return
  }
  uploading.value = true
  try {
    const res = await uploadCommunityImage(file)
    if (res?.imageUrl) pendingImages.value.push(res.imageUrl)
  } catch (e) {
    ElMessage.error(e?.message || '上传失败')
  } finally {
    uploading.value = false
  }
}

async function publish() {
  if (!canPublish.value || sending.value) return
  sending.value = true
  try {
    const created = await createCommunityPost({
      content: draft.value.trim(),
      imageUrls: [...pendingImages.value],
      linkedCharacterId: linkedCharacterId.value || undefined
    })
    draft.value = ''
    pendingImages.value = []
    linkedCharacterId.value = null
    posts.value = [created, ...posts.value]
    ElMessage.success('已提交，审核通过后会出现在广场')
  } catch (e) {
    ElMessage.error(e?.message || '发布失败')
  } finally {
    sending.value = false
  }
}

async function onLike(post) {
  try {
    const res = await toggleCommunityLike(post.id)
    post.likedByMe = !!res?.liked
    post.likeCount = res?.likeCount ?? post.likeCount
  } catch (e) {
    ElMessage.error(e?.message || '操作失败')
  }
}

function toggleComments(post) {
  openCommentsId.value = openCommentsId.value === post.id ? null : post.id
}

async function onDelete(post) {
  try {
    await ElMessageBox.confirm('确定删除这条动态？', '删除确认', { type: 'warning' })
    await deleteCommunityPost(post.id)
    posts.value = posts.value.filter((p) => p.id !== post.id)
  } catch (e) {
    if (e !== 'cancel') ElMessage.error(e?.message || '删除失败')
  }
}
</script>

<style lang="scss" scoped>
.community-feed {
  display: flex;
  flex-direction: column;
  gap: $space-5;
  margin-top: $space-8;
}

.community-feed__eyebrow {
  display: inline-block;
  font-size: $font-size-xs;
  font-weight: $font-weight-medium;
  letter-spacing: 0.16em;
  color: $color-pink-primary;
  opacity: 0.9;
}

.community-feed__stack {
  display: flex;
  flex-direction: column;
  gap: $space-5;
}

.feed-compose-card {
  padding: $space-5;
  border-radius: $radius-lg;
  display: flex;
  flex-direction: column;
  gap: $space-4;
}

.feed-compose-card__label {
  font-size: $font-size-xs;
  font-weight: $font-weight-medium;
  letter-spacing: 0.16em;
  color: $color-pink-primary;
  opacity: 0.9;
}

.compose-actions {
  display: flex;
  gap: $space-3;
  justify-content: flex-end;
}

.compose-images {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(88px, 1fr));
  gap: $space-2;
}

.compose-images__item {
  position: relative;
  aspect-ratio: 1;
  border-radius: $radius-md;
  overflow: hidden;

  img {
    width: 100%;
    height: 100%;
    object-fit: cover;
  }
}

.compose-images__remove {
  position: absolute;
  top: 4px;
  right: 4px;
  width: 22px;
  height: 22px;
  border: none;
  border-radius: $radius-full;
  background: var(--ly-bg-elevated);
  color: var(--ly-text-primary);
  cursor: pointer;
}

.compose-character {
  display: flex;
  flex-direction: column;
  gap: $space-2;
}

.compose-character__select {
  width: 100%;
}

.compose-character__chip {
  display: inline-flex;
  align-items: center;
  gap: $space-2;
  align-self: flex-start;
  padding: $space-1 $space-3 $space-1 $space-1;
  border-radius: $radius-full;
  background: color-mix(in srgb, var(--ly-accent) 10%, transparent);
  color: var(--ly-accent);
  font-size: $font-size-sm;
}

.compose-character__chip-avatar {
  width: 24px;
  height: 24px;
  border-radius: $radius-full;
  overflow: hidden;
  background: var(--ly-bg-elevated);
  display: grid;
  place-items: center;

  img {
    width: 100%;
    height: 100%;
    object-fit: cover;
  }
}

.compose-share-loading {
  padding: $space-3 $space-4;
  border-radius: $radius-md;
  background: color-mix(in srgb, var(--ly-accent) 8%, transparent);
  color: var(--ly-text-secondary);
  font-size: $font-size-sm;
}

.feed-empty {
  padding: $space-10;
  text-align: center;
  color: var(--ly-text-muted);
  border-radius: $radius-lg;
  margin-top: $space-8;
}

.load-more {
  display: flex;
  justify-content: center;
  margin: $space-6 0 $space-4;
}
</style>
