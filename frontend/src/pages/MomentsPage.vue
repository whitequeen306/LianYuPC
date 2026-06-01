<template>
  <div class="moments-page stagger-container">
    <header class="page-header">
      <h1 class="page-title">{{ t('moments.title') }}</h1>
      <p class="page-desc">{{ t('moments.desc') }}</p>
    </header>

    <div class="filter-row stagger-item">
      <el-select
        v-model="filterCharId"
        :placeholder="t('moments.allCharacters')"
        clearable
        style="width: 200px"
        @change="reloadFeed"
      >
        <el-option
          v-for="c in characters"
          :key="c.id"
          :label="c.name"
          :value="c.id"
        />
      </el-select>
      <el-button :icon="RefreshRight" :loading="loading" @click="reloadFeed">
        {{ t('moments.refresh') }}
      </el-button>
    </div>

    <div v-if="loading && posts.length === 0" class="loading-state">
      <el-icon class="is-loading" :size="24"><Loading /></el-icon>
      <span>{{ t('common.loading') }}</span>
    </div>

    <div v-else-if="posts.length === 0" class="empty-state glass stagger-item">
      <div class="empty-icon">
        <el-icon :size="40"><PictureRounded /></el-icon>
      </div>
      <h3>{{ t('moments.empty') }}</h3>
      <p>{{ t('moments.emptyDesc') }}</p>
    </div>

    <div v-else class="moments-feed">
      <article
        v-for="(post, idx) in posts"
        :key="post.id"
        class="moment-card glass stagger-item"
        :style="{ animationDelay: `${idx * 0.04}s` }"
      >
        <div class="moment-card__head">
          <div class="moment-card__avatar">
            <img v-if="post.characterAvatarUrl" :src="resolveMediaUrl(post.characterAvatarUrl)" :alt="post.characterName" />
            <el-icon v-else :size="18"><User /></el-icon>
          </div>
          <div class="moment-card__meta">
            <span class="moment-card__name">{{ post.characterName }}</span>
            <span class="moment-card__time">{{ formatTime(post.createdAt) }}</span>
          </div>
          <span class="moment-card__tag" :class="`moment-card__tag--${post.postType?.toLowerCase()}`">
            {{ typeLabel(post.postType) }}
          </span>
        </div>
        <p class="moment-card__content">{{ post.content }}</p>

        <section class="moment-comments">
          <div class="moment-comments__head">
            <span>{{ t('moments.commentsTitle') }}</span>
            <span class="moment-comments__count">{{ commentCount(post) }}</span>
          </div>

          <div v-if="commentsLoading[post.id]" class="comments-loading">
            <el-icon class="is-loading" :size="16"><Loading /></el-icon>
          </div>

          <ul v-else-if="getComments(post.id).length" class="comment-list">
            <li
              v-for="c in getComments(post.id)"
              :key="c.id"
              class="comment-item"
              :class="{ 'comment-item--reply': c.parentId }"
            >
              <div class="comment-item__avatar">
                <img v-if="c.characterAvatarUrl" :src="resolveMediaUrl(c.characterAvatarUrl)" />
                <el-icon v-else :size="14"><User /></el-icon>
              </div>
              <div class="comment-item__body">
                <div class="comment-item__meta">
                  <span class="comment-item__name">{{ c.characterName || t('moments.you') }}</span>
                  <span class="comment-item__time">{{ formatTime(c.createdAt) }}</span>
                </div>
                <p class="comment-item__text">{{ c.content }}</p>
                <button type="button" class="comment-reply-btn" @click="setReplyTarget(post, c)">
                  {{ t('moments.reply') }}
                </button>
              </div>
            </li>
          </ul>

          <p v-else class="comments-empty">{{ t('moments.noComments') }}</p>

          <div class="comment-compose">
            <p v-if="replyTarget[post.id]" class="reply-hint">
              {{ t('moments.replyingTo', { name: replyTarget[post.id].name }) }}
              <button type="button" class="link-btn" @click="clearReply(post.id)">{{ t('common.cancel') }}</button>
            </p>
            <div class="comment-compose__row">
              <el-input
                v-model="draftByPost[post.id]"
                type="textarea"
                :rows="2"
                :placeholder="t('moments.commentPlaceholder')"
                resize="none"
                @keydown.enter.exact.prevent="submitComment(post)"
              />
              <el-button
                type="primary"
                :loading="sending[post.id]"
                :disabled="!draftByPost[post.id]?.trim()"
                @click="submitComment(post)"
              >
                {{ t('moments.sendComment') }}
              </el-button>
            </div>
          </div>
        </section>

        <div v-if="post.conversationId" class="moment-card__foot">
          <el-button text size="small" @click="goChat(post)">
            {{ t('moments.goChat') }}
          </el-button>
        </div>
      </article>

      <div v-if="hasMore" class="load-more">
        <el-button :loading="loadingMore" @click="loadMore">{{ t('moments.loadMore') }}</el-button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { onMounted, onUnmounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import { Loading, PictureRounded, RefreshRight, User } from '@element-plus/icons-vue'
import { listCharacters } from '@/api/character'
import {
  addMomentComment,
  fetchMomentComments,
  fetchMomentsFeed,
  markMomentsSeen
} from '@/api/moments'
import { resolveMediaUrl } from '@/utils/media'

const { t } = useI18n()
const router = useRouter()

const characters = ref([])
const posts = ref([])
const filterCharId = ref(null)
const loading = ref(false)
const loadingMore = ref(false)
const cursor = ref(null)
const hasMore = ref(false)

const commentsByPost = ref({})
const commentsLoading = reactive({})
const draftByPost = reactive({})
const replyTarget = reactive({})
const sending = reactive({})

let pollTimer = null

onMounted(async () => {
  try {
    characters.value = await listCharacters() || []
  } catch {
    characters.value = []
  }
  await reloadFeed()
  try {
    await markMomentsSeen()
  } catch {
    /* ignore */
  }
  startCommentPolling()
})

onUnmounted(() => {
  stopCommentPolling()
})

function startCommentPolling() {
  stopCommentPolling()
  pollTimer = setInterval(() => {
    for (const post of posts.value) {
      loadComments(post.id, true)
    }
  }, 4000)
}

function stopCommentPolling() {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

async function reloadFeed() {
  loading.value = true
  cursor.value = null
  posts.value = []
  commentsByPost.value = {}
  try {
    const data = await fetchMomentsFeed({
      characterId: filterCharId.value || undefined,
      limit: 20
    })
    posts.value = data?.items || []
    cursor.value = data?.nextCursor ?? null
    hasMore.value = !!data?.hasMore
    for (const post of posts.value) {
      if (draftByPost[post.id] === undefined) draftByPost[post.id] = ''
      await loadComments(post.id, false)
    }
  } finally {
    loading.value = false
  }
}

async function loadMore() {
  if (!hasMore.value || loadingMore.value) return
  loadingMore.value = true
  try {
    const data = await fetchMomentsFeed({
      characterId: filterCharId.value || undefined,
      cursor: cursor.value,
      limit: 20
    })
    const items = data?.items || []
    posts.value = [...posts.value, ...items]
    cursor.value = data?.nextCursor ?? null
    hasMore.value = !!data?.hasMore
    for (const post of items) {
      if (draftByPost[post.id] === undefined) draftByPost[post.id] = ''
      await loadComments(post.id, false)
    }
  } finally {
    loadingMore.value = false
  }
}

async function loadComments(postId, silent = false) {
  if (!postId) return
  if (!silent) commentsLoading[postId] = true
  try {
    const data = await fetchMomentComments(postId, { limit: 80 })
    commentsByPost.value = {
      ...commentsByPost.value,
      [postId]: data?.items || []
    }
  } catch {
    if (!silent) commentsByPost.value = { ...commentsByPost.value, [postId]: [] }
  } finally {
    if (!silent) commentsLoading[postId] = false
  }
}

function getComments(postId) {
  return commentsByPost.value[postId] || []
}

function commentCount(post) {
  const loaded = getComments(post.id).length
  if (loaded > 0) return loaded
  return post.commentCount ?? 0
}

function setReplyTarget(post, comment) {
  replyTarget[post.id] = {
    commentId: comment.id,
    name: comment.characterName || t('moments.you')
  }
}

function clearReply(postId) {
  delete replyTarget[postId]
}

async function submitComment(post) {
  const text = (draftByPost[post.id] || '').trim()
  if (!text || sending[post.id]) return

  sending[post.id] = true
  try {
    const payload = { content: text }
    const target = replyTarget[post.id]
    if (target?.commentId) {
      payload.parentId = target.commentId
    }
    await addMomentComment(post.id, payload)
    draftByPost[post.id] = ''
    clearReply(post.id)
    ElMessage.success(t('moments.commentSent'))
    await loadComments(post.id, true)
    setTimeout(() => loadComments(post.id, true), 2500)
    setTimeout(() => loadComments(post.id, true), 6000)
  } catch {
    /* http interceptor */
  } finally {
    sending[post.id] = false
  }
}

function typeLabel(type) {
  const map = {
    MOOD: t('moments.typeMood'),
    REFLECTION: t('moments.typeReflection'),
    SYSTEM: t('moments.typeSystem')
  }
  return map[type] || type
}

function formatTime(iso) {
  if (!iso) return ''
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return iso
  const now = new Date()
  const sameDay = d.toDateString() === now.toDateString()
  if (sameDay) {
    return d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
  }
  return d.toLocaleString([], { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' })
}

function goChat(post) {
  if (!post.conversationId) return
  router.push(`/app/chat/${post.conversationId}`)
}
</script>

<style lang="scss" scoped>
.moments-page {
  max-width: 720px;
  margin: 0 auto;
  padding: 24px 20px 48px;
}

.page-header {
  margin-bottom: 20px;
}

.page-title {
  margin: 0 0 8px;
  font-size: 1.5rem;
  font-weight: 600;
}

.page-desc {
  margin: 0;
  font-size: 0.88rem;
  color: rgba(255, 255, 255, 0.55);
}

.filter-row {
  display: flex;
  gap: 12px;
  align-items: center;
  margin-bottom: 20px;
  flex-wrap: wrap;
}

.loading-state,
.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
  padding: 48px 24px;
  text-align: center;
  color: rgba(255, 255, 255, 0.6);
}

.empty-state h3 {
  margin: 0;
  color: rgba(255, 255, 255, 0.9);
}

.empty-state p {
  margin: 0;
  font-size: 0.85rem;
  max-width: 320px;
}

.moments-feed {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.moment-card {
  padding: 16px 18px;
  border-radius: $radius-lg;
}

.moment-card__head {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
}

.moment-card__avatar {
  width: 40px;
  height: 40px;
  border-radius: 12px;
  overflow: hidden;
  background: rgba(255, 255, 255, 0.06);
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;

  img {
    width: 100%;
    height: 100%;
    object-fit: cover;
  }
}

.moment-card__meta {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.moment-card__name {
  font-weight: 600;
  font-size: 0.92rem;
}

.moment-card__time {
  font-size: 0.72rem;
  color: rgba(255, 255, 255, 0.45);
}

.moment-card__tag {
  flex-shrink: 0;
  font-size: 0.68rem;
  padding: 4px 10px;
  border-radius: $radius-pill;
  letter-spacing: 0.04em;
  background: rgba(255, 255, 255, 0.08);
  color: rgba(255, 255, 255, 0.75);

  &--mood {
    background: rgba(244, 166, 181, 0.15);
    color: #f4a6b5;
  }

  &--reflection {
    background: rgba(147, 197, 253, 0.12);
    color: #93c5fd;
  }

  &--system {
    background: rgba(167, 139, 250, 0.12);
    color: #c4b5fd;
  }
}

.moment-card__content {
  margin: 0 0 12px;
  line-height: 1.65;
  font-size: 0.92rem;
  color: rgba(255, 255, 255, 0.88);
  white-space: pre-wrap;
  word-break: break-word;
}

.moment-comments {
  margin-top: 8px;
  padding-top: 12px;
  border-top: 1px solid rgba(255, 255, 255, 0.08);
}

.moment-comments__head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;
  font-size: 0.82rem;
  color: rgba(255, 255, 255, 0.55);
}

.moment-comments__count {
  font-size: 0.72rem;
  padding: 2px 8px;
  border-radius: $radius-pill;
  background: rgba(255, 255, 255, 0.08);
}

.comments-loading {
  padding: 8px 0;
  color: rgba(255, 255, 255, 0.5);
}

.comments-empty {
  margin: 0 0 10px;
  font-size: 0.8rem;
  color: rgba(255, 255, 255, 0.4);
}

.comment-list {
  list-style: none;
  margin: 0 0 12px;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.comment-item {
  display: flex;
  gap: 10px;

  &--reply {
    margin-left: 20px;
  }
}

.comment-item__avatar {
  width: 28px;
  height: 28px;
  border-radius: 8px;
  overflow: hidden;
  background: rgba(255, 255, 255, 0.06);
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: center;

  img {
    width: 100%;
    height: 100%;
    object-fit: cover;
  }
}

.comment-item__body {
  flex: 1;
  min-width: 0;
}

.comment-item__meta {
  display: flex;
  align-items: baseline;
  gap: 8px;
  margin-bottom: 4px;
}

.comment-item__name {
  font-size: 0.8rem;
  font-weight: 600;
  color: rgba(255, 255, 255, 0.9);
}

.comment-item__time {
  font-size: 0.68rem;
  color: rgba(255, 255, 255, 0.4);
}

.comment-item__text {
  margin: 0;
  font-size: 0.85rem;
  line-height: 1.5;
  color: rgba(255, 255, 255, 0.82);
}

.comment-reply-btn {
  margin-top: 4px;
  padding: 0;
  border: none;
  background: none;
  font-size: 0.72rem;
  color: rgba(244, 166, 181, 0.85);
  cursor: pointer;
}

.comment-compose {
  margin-top: 8px;
}

.reply-hint {
  margin: 0 0 6px;
  font-size: 0.75rem;
  color: rgba(255, 255, 255, 0.55);
}

.link-btn {
  margin-left: 8px;
  padding: 0;
  border: none;
  background: none;
  color: rgba(244, 166, 181, 0.9);
  cursor: pointer;
  font-size: inherit;
}

.comment-compose__row {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.moment-card__foot {
  margin-top: 10px;
  padding-top: 8px;
  border-top: 1px solid rgba(255, 255, 255, 0.06);
}

.load-more {
  display: flex;
  justify-content: center;
  padding: 8px 0 24px;
}
</style>
