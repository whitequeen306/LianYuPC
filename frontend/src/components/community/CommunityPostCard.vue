<template>
  <article class="community-post-card glass" :class="{ 'is-pending': post.status === 'pending', 'is-rejected': post.status === 'rejected' }">
    <header class="community-post-card__head">
      <button type="button" class="community-post-card__author" @click="goAuthor">
        <span class="community-post-card__avatar">
          <img v-if="post.avatarUrl" :src="resolveMediaUrl(post.avatarUrl)" alt="" />
          <el-icon v-else :size="18"><User /></el-icon>
        </span>
        <span class="community-post-card__meta">
          <span class="community-post-card__name">{{ post.nickname || '用户' }}</span>
          <span class="community-post-card__time">{{ timeLabel }}</span>
        </span>
      </button>
      <span v-if="statusLabel" class="community-post-card__status">{{ statusLabel }}</span>
      <el-button
        v-if="canDelete"
        text
        type="danger"
        size="small"
        :loading="deleting"
        @click="$emit('delete', post)"
      >
        删除
      </el-button>
    </header>

    <div v-if="post.linkedCharacterId && post.linkedCharacterName" class="community-post-card__linked">
      <span class="community-post-card__linked-avatar">
        <CharacterAvatarImg
          :character-id="post.linkedCharacterId"
          :characters="charactersStore.list"
          :character-avatar-url="post.linkedCharacterAvatarUrl || ''"
          :character-avatar-thumb-url="post.linkedCharacterAvatarThumbUrl || post.linkedCharacterAvatarUrl || ''"
          :icon-size="14"
          alt=""
        />
      </span>
      <span class="community-post-card__linked-name">{{ post.linkedCharacterName }}</span>
    </div>

    <p v-if="post.content" class="community-post-card__body" v-html="sanitizeHtml(post.content)" />

    <div
      v-if="images.length"
      class="community-post-card__images"
      :class="[`count-${Math.min(images.length, 3)}`, { 'is-multi': images.length > 1 }]"
    >
      <img
        v-for="(src, idx) in images"
        :key="`${src}-${idx}`"
        :src="resolveMediaUrl(src)"
        alt=""
        loading="lazy"
        @click="openImagePreview(idx)"
      />
    </div>

    <p v-if="post.status === 'rejected' && post.rejectReason" class="community-post-card__reject">
      {{ post.rejectReason }}
    </p>

    <footer class="community-post-card__actions">
      <button type="button" class="action-btn" :class="{ active: post.likedByMe }" @click="$emit('like', post)">
        <el-icon :size="16"><StarFilled v-if="post.likedByMe" /><Star v-else /></el-icon>
        <span>{{ post.likeCount || 0 }}</span>
      </button>
      <button type="button" class="action-btn" @click="$emit('toggle-comments', post)">
        <el-icon :size="16"><ChatDotRound /></el-icon>
        <span>{{ post.commentCount || 0 }}</span>
      </button>
    </footer>

    <div v-if="showComments" class="community-post-card__comments">
      <div v-for="c in comments" :key="c.id" class="comment-row">
        <button type="button" class="comment-author" @click="goUser(c.authorUserId)">
          {{ c.nickname || '用户' }}
        </button>
        <span class="comment-body">{{ c.content }}</span>
      </div>
      <div class="comment-compose">
        <el-input
          v-model="draft"
          size="small"
          maxlength="512"
          placeholder="写一条评论…"
          @keyup.enter="submitComment"
        />
        <el-button type="primary" size="small" :loading="commentSending" :disabled="!draft.trim()" @click="submitComment">
          发送
        </el-button>
      </div>
    </div>

    <el-image-viewer
      v-if="imageViewerVisible"
      :url-list="imageViewerUrlList"
      :initial-index="imageViewerInitialIndex"
      :z-index="10000"
      teleported
      hide-on-click-modal
      @close="imageViewerVisible = false"
    />
  </article>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { User, Star, StarFilled, ChatDotRound } from '@element-plus/icons-vue'
import { resolveMediaUrl } from '@/utils/media'
import { getElectronAPI } from '@/utils/electron'
import { sanitizeHtml } from '@/utils/sanitize'
import { formatFeedTime } from '@/utils/feedTime'
import { addCommunityComment, fetchCommunityComments } from '@/api/community'
import { useUserStore } from '@/stores/user'
import { useCharactersStore } from '@/stores/characters'
import CharacterAvatarImg from '@/components/CharacterAvatarImg.vue'
import { ElMessage } from 'element-plus'

const props = defineProps({
  post: { type: Object, required: true },
  showComments: { type: Boolean, default: false },
  allowDelete: { type: Boolean, default: false }
})

defineEmits(['like', 'toggle-comments', 'delete', 'commented'])

const router = useRouter()
const { t, locale } = useI18n()
const userStore = useUserStore()
const charactersStore = useCharactersStore()

const comments = ref([])
const draft = ref('')
const commentSending = ref(false)
const deleting = ref(false)
const imageViewerVisible = ref(false)
const imageViewerUrlList = ref([])
const imageViewerInitialIndex = ref(0)
const images = computed(() => props.post.imageUrls || [])
const resolvedImages = computed(() => images.value.map(src => resolveMediaUrl(src)))
const timeLabel = computed(() => formatFeedTime(props.post.createdAt, t, locale.value))
const canDelete = computed(() =>
  props.allowDelete && props.post.authorUserId === userStore.userId
)
const statusLabel = computed(() => {
  if (props.post.status === 'pending') return '审核中'
  if (props.post.status === 'rejected') return '未通过'
  return ''
})

watch(() => props.post.linkedCharacterAvatarUrl, () => {
  linkedAvatarBroken.value = false
  linkedAvatarTier.value = 'thumb'
})

watch(() => props.post.linkedCharacterId, () => {
  linkedAvatarBroken.value = false
  linkedAvatarTier.value = 'thumb'
})

watch(() => props.showComments, async (open) => {
  if (open) await loadComments()
}, { immediate: true })

async function loadComments() {
  try {
    const data = await fetchCommunityComments(props.post.id, { limit: 50 })
    comments.value = data?.items || []
  } catch {
    comments.value = []
  }
}

async function submitComment() {
  const content = draft.value.trim()
  if (!content || commentSending.value) return
  commentSending.value = true
  try {
    const created = await addCommunityComment(props.post.id, { content })
    comments.value = [...comments.value, created]
    draft.value = ''
    props.post.commentCount = (props.post.commentCount || 0) + 1
  } catch (e) {
    ElMessage.error(e?.message || '评论失败')
  } finally {
    commentSending.value = false
  }
}

function openImagePreview(index) {
  const urls = resolvedImages.value
  if (!urls.length) return
  const initialIndex = Math.min(Math.max(index, 0), urls.length - 1)

  const ea = getElectronAPI()
  if (ea && typeof ea.openImageViewer === 'function') {
    ea.openImageViewer({ urls, initialIndex })
    return
  }

  imageViewerUrlList.value = urls
  imageViewerInitialIndex.value = initialIndex
  imageViewerVisible.value = true
}

function goAuthor() {
  goUser(props.post.authorUserId)
}

function goUser(userId) {
  if (!userId) return
  if (userId === userStore.userId) {
    router.push('/app/profile')
    return
  }
  router.push(`/app/users/${userId}`)
}
</script>

<style lang="scss" scoped>
.community-post-card {
  padding: $space-5;
  border-radius: $radius-lg;
  display: flex;
  flex-direction: column;
  gap: $space-4;
  transition:
    transform 0.24s cubic-bezier(0.23, 1, 0.32, 1),
    border-color 0.24s cubic-bezier(0.23, 1, 0.32, 1),
    box-shadow 0.24s cubic-bezier(0.23, 1, 0.32, 1);

  &:hover {
    transform: translateY(-2px);
    border-color: color-mix(in srgb, var(--ly-accent) 22%, transparent);
    box-shadow: $shadow-glow-pink;
  }
}

.community-post-card__head {
  display: flex;
  align-items: center;
  gap: $space-3;
}

.community-post-card__author {
  display: flex;
  align-items: center;
  gap: $space-3;
  background: none;
  border: none;
  color: inherit;
  cursor: pointer;
  padding: 0;
  flex: 1;
  min-width: 0;
  text-align: left;
}

.community-post-card__avatar {
  width: 44px;
  height: 44px;
  border-radius: $radius-full;
  overflow: hidden;
  background: var(--ly-bg-elevated);
  display: grid;
  place-items: center;
  flex-shrink: 0;
  color: var(--ly-text-muted);

  img {
    width: 100%;
    height: 100%;
    object-fit: cover;
  }
}

.community-post-card__meta {
  display: flex;
  flex-direction: column;
  gap: $space-1;
  min-width: 0;
}

.community-post-card__name {
  font-weight: $font-weight-semibold;
  color: var(--ly-text-primary);
  line-height: 1.2;
}

.community-post-card__time,
.community-post-card__status {
  font-size: $font-size-xs;
  color: var(--ly-text-muted);
  line-height: 1.2;
}

.community-post-card__status {
  padding: $space-1 $space-3;
  border-radius: $radius-full;
  background: color-mix(in srgb, var(--ly-accent) 12%, transparent);
  color: var(--ly-accent);
  font-weight: $font-weight-medium;
}

.community-post-card__linked {
  display: inline-flex;
  align-items: center;
  gap: $space-2;
  align-self: flex-start;
  padding: $space-1 $space-3 $space-1 $space-1;
  border-radius: $radius-full;
  background: color-mix(in srgb, var(--ly-accent) 10%, transparent);
  color: var(--ly-accent);
  font-size: $font-size-sm;
  font-weight: $font-weight-medium;
}

.community-post-card__linked-avatar {
  width: 24px;
  height: 24px;
  border-radius: $radius-full;
  overflow: hidden;
  background: var(--ly-bg-elevated);
  display: grid;
  place-items: center;
  flex-shrink: 0;

  img {
    width: 100%;
    height: 100%;
    object-fit: cover;
  }
}

.community-post-card__linked-name {
  line-height: 1.2;
  padding-right: $space-1;
}

.community-post-card__body {
  margin: 0;
  color: var(--ly-text-primary);
  font-size: $font-size-base;
  line-height: $line-height-normal;
  white-space: pre-wrap;
  word-break: break-word;
}

.community-post-card__images {
  display: grid;
  gap: $space-2;

  &.count-1 {
    grid-template-columns: minmax(0, max-content);
    justify-items: start;
  }

  &.count-2 { grid-template-columns: repeat(2, 1fr); }
  &.count-3 { grid-template-columns: repeat(3, 1fr); }

  img {
    border-radius: $radius-md;
    cursor: pointer;
    transition: opacity 0.22s cubic-bezier(0.23, 1, 0.32, 1);

    &:hover {
      opacity: 0.92;
    }
  }

  // 单图：按原比例完整展示，不裁切
  &.count-1 img {
    display: block;
    width: auto;
    max-width: 100%;
    height: auto;
    max-height: min(400px, 70vh);
    object-fit: contain;
  }

  // 多图：统一方形瓦片，网格整齐不参差
  &.is-multi img {
    width: 100%;
    aspect-ratio: 1;
    object-fit: cover;
  }
}

.community-post-card__reject {
  margin: 0;
  padding: $space-3 $space-4;
  border-radius: $radius-md;
  background: color-mix(in srgb, var(--ly-error) 12%, transparent);
  font-size: $font-size-sm;
  color: var(--ly-error);
}

.community-post-card__actions {
  display: flex;
  gap: $space-2;
  padding-top: $space-4;
  border-top: 1px solid color-mix(in srgb, var(--ly-accent) 10%, transparent);
}

.action-btn {
  display: inline-flex;
  align-items: center;
  gap: $space-2;
  background: none;
  border: none;
  color: var(--ly-text-secondary);
  cursor: pointer;
  padding: $space-2 $space-3;
  border-radius: $radius-full;
  font-size: $font-size-sm;
  transition:
    color 0.22s cubic-bezier(0.23, 1, 0.32, 1),
    background 0.22s cubic-bezier(0.23, 1, 0.32, 1);

  &:hover {
    color: var(--ly-accent);
    background: color-mix(in srgb, var(--ly-accent) 8%, transparent);
  }

  &.active {
    color: var(--ly-accent);
  }
}

.community-post-card__comments {
  border-top: 1px solid color-mix(in srgb, var(--ly-accent) 12%, transparent);
  padding-top: $space-4;
  display: flex;
  flex-direction: column;
  gap: $space-3;
}

.comment-row {
  font-size: $font-size-sm;
  line-height: $line-height-normal;
  color: var(--ly-text-secondary);
}

.comment-author {
  background: none;
  border: none;
  color: var(--ly-accent);
  cursor: pointer;
  padding: 0;
  margin-right: $space-2;
  font-weight: $font-weight-medium;
}

.comment-compose {
  display: flex;
  gap: $space-2;
  margin-top: $space-1;
}

.is-pending {
  opacity: 0.85;
}
</style>
