<template>
  <div class="moments-page companion-page companion-feed stagger-container">
    <header class="companion-hero stagger-item">
      <span class="companion-eyebrow">{{ t('feed.eyebrow') }}</span>
      <h1 class="companion-title">{{ t('moments.title') }}</h1>
      <p class="companion-lead">{{ t('moments.desc') }}</p>
    </header>

    <div class="moments-layout">
      <div class="moments-main">
        <div class="feed-toolbar stagger-item">
          <div class="feed-chip-bar">
            <button
              type="button"
              class="feed-chip"
              :class="{ active: !filterCharId }"
              @click="setFilter(null)"
            >
              {{ t('moments.allCharacters') }}
            </button>
            <button
              v-for="c in charactersStore.list"
              :key="c.id"
              type="button"
              class="feed-chip"
              :class="{ active: filterCharId === c.id }"
              @click="setFilter(c.id)"
            >
              {{ c.name }}
            </button>
          </div>
          <button
            type="button"
            class="feed-icon-btn"
            :class="{ spinning: loading }"
            :aria-label="t('moments.refresh')"
            @click="reloadFeed"
          >
            <el-icon :size="16"><RefreshRight /></el-icon>
          </button>
        </div>

        <div v-if="!filterCharId" v-tilt class="feed-compose-card glass stagger-item">
          <div class="feed-compose-card__head">
            <div class="feed-card__avatar feed-compose-card__avatar">
              <img
                v-if="userStore.avatarUrl"
                :src="resolveMediaUrl(userStore.avatarUrl)"
                alt=""
              />
              <el-icon v-else :size="18"><User /></el-icon>
            </div>
            <span class="feed-compose-card__title">{{ t('moments.composeTitle') }}</span>
          </div>
          <el-input
            v-model="userPostDraft"
            type="textarea"
            :rows="2"
            :autosize="{ minRows: 2, maxRows: 5 }"
            :placeholder="t('moments.composePlaceholder')"
            resize="none"
            maxlength="512"
            show-word-limit
          />
          <div class="feed-compose-card__actions">
            <el-button
              v-bubble-btn
              type="primary"
              :loading="userPostSending"
              :disabled="!userPostDraft.trim()"
              @click="submitUserPost"
            >
              {{ t('moments.publish') }}
            </el-button>
          </div>
        </div>

        <div v-if="loading && posts.length === 0" class="feed-empty glass stagger-item">
          <el-icon class="is-loading" :size="28"><Loading /></el-icon>
          <p>{{ t('common.loading') }}</p>
        </div>

        <div v-else-if="posts.length === 0" class="feed-empty glass stagger-item">
          <div class="empty-icon">
            <el-icon :size="40"><PictureRounded /></el-icon>
          </div>
          <h3>{{ t('moments.empty') }}</h3>
          <p>{{ t('moments.emptyDesc') }}</p>
        </div>

        <div v-else class="social-feed">
          <template v-for="item in feedTimeline" :key="item.key">
            <div v-if="item.kind === 'divider'" class="feed-date-divider">
              {{ item.label }}
            </div>

            <article
              v-else
              v-tilt
              class="feed-card glass stagger-item"
              :class="[
                postTypeClass(item.post.postType),
                { 'feed-hero-card': item.isHero }
              ]"
              :style="{ animationDelay: `${item.idx * 0.04}s` }"
            >
              <div class="feed-card__head">
                <div class="feed-card__avatar">
                  <CharacterAvatarImg
                    v-if="item.post.authorType !== 'USER'"
                    :character-id="item.post.characterId"
                    :characters="charactersStore.list"
                    :character-avatar-url="item.post.characterAvatarUrl || ''"
                    :character-avatar-thumb-url="item.post.characterAvatarThumbUrl || ''"
                    :alt="postAuthorName(item.post)"
                    :icon-size="18"
                  />
                  <img
                    v-else-if="userAuthorAvatar(item.post)"
                    :src="resolveMediaUrl(userAuthorAvatar(item.post))"
                    :alt="postAuthorName(item.post)"
                  >
                  <el-icon v-else :size="18"><User /></el-icon>
                </div>
                <div class="feed-card__meta">
                  <span class="feed-card__name">{{ postAuthorName(item.post) }}</span>
                  <span class="feed-card__time">{{ formatTime(item.post.createdAt) }}</span>
                </div>
                <span
                  v-if="item.post.authorType !== 'USER'"
                  class="feed-card__badge"
                  :class="`feed-card__badge--${item.post.postType?.toLowerCase()}`"
                >
                  {{ typeLabel(item.post.postType) }}
                </span>
              </div>

              <p class="feed-card__body">{{ item.post.content }}</p>

              <div
                v-if="!expandedComments[item.post.id] && commentStripHtml(item.post)"
                class="feed-comment-strip"
                @click="toggleComments(item.post, true)"
              >
                <el-icon :size="14"><ChatDotRound /></el-icon>
                <p v-html="sanitizeHtml(commentStripHtml(item.post))" />
              </div>

              <div class="feed-card__actions">
                <button
                  type="button"
                  class="feed-action-btn"
                  :class="{ active: expandedComments[item.post.id] }"
                  @click="toggleComments(item.post)"
                >
                  {{ commentActionLabel(item.post) }}
                </button>
                <button
                  v-if="item.post.conversationId"
                  type="button"
                  class="feed-action-btn"
                  @click="goChat(item.post)"
                >
                  {{ t('moments.goChat') }}
                </button>
              </div>

              <div v-if="expandedComments[item.post.id]" class="feed-comment-zone">
                <div v-if="commentsLoading[item.post.id]" class="feed-comment-preview">
                  <el-icon class="is-loading" :size="14"><Loading /></el-icon>
                </div>

                <template v-else-if="threadedComments(item.post.id).length">
                  <div
                    class="feed-comment-scroll"
                    :class="{ 'feed-comment-scroll--expanded': commentsFullyExpanded[item.post.id] }"
                  >
                    <ul class="feed-comment-list">
                      <li
                        v-for="c in threadedComments(item.post.id)"
                        :key="c.id"
                        class="feed-comment-item"
                      >
                        <div class="feed-comment-item__row">
                          <span class="feed-comment-item__name">{{ formatCommentAuthorLabel(c) }}</span>
                          <span>{{ c.content }}</span>
                        </div>
                        <button type="button" class="feed-comment-item__reply" @click="setReplyTarget(item.post, c)">
                          {{ t('moments.reply') }}
                        </button>

                        <ul v-if="c.replies?.length" class="feed-comment-reply-list">
                          <li
                            v-for="reply in c.replies"
                            :key="reply.id"
                            class="feed-comment-item feed-comment-item--reply"
                          >
                            <div class="feed-comment-item__row">
                              <span class="feed-comment-item__name">{{ formatCommentAuthorLabel(reply) }}</span>
                              <span>{{ reply.content }}</span>
                            </div>
                            <button type="button" class="feed-comment-item__reply" @click="setReplyTarget(item.post, reply)">
                              {{ t('moments.reply') }}
                            </button>
                          </li>
                        </ul>
                      </li>
                    </ul>
                  </div>

                  <button
                    v-if="commentCount(item.post) > 6"
                    type="button"
                    class="feed-comment-fold"
                    @click="toggleCommentsFullyExpanded(item.post.id)"
                  >
                    {{ commentsFullyExpanded[item.post.id]
                      ? t('feed.collapseComments')
                      : t('feed.expandAllComments', { n: commentCount(item.post) }) }}
                  </button>
                </template>

                <p v-else class="feed-comment-preview">{{ t('moments.noComments') }}</p>

                <div class="feed-compose">
                  <div class="feed-compose__input">
                    <p v-if="replyTarget[item.post.id]" class="reply-hint">
                      {{ t('moments.replyingTo', { name: replyTarget[item.post.id].name }) }}
                      <button type="button" class="link-btn" @click="clearReply(item.post.id)">
                        {{ t('common.cancel') }}
                      </button>
                    </p>
                    <el-input
                      v-model="draftByPost[item.post.id]"
                      type="textarea"
                      :rows="1"
                      :autosize="{ minRows: 1, maxRows: 4 }"
                      :placeholder="t('moments.commentPlaceholder')"
                      resize="none"
                      @keydown.enter.exact.prevent="submitComment(item.post)"
                    />
                  </div>
                  <el-button
                    type="primary"
                    circle
                    :loading="sending[item.post.id]"
                    :disabled="!draftByPost[item.post.id]?.trim()"
                    @click="submitComment(item.post)"
                  >
                    <el-icon><Promotion /></el-icon>
                  </el-button>
                </div>
              </div>
            </article>
          </template>

          <div v-if="hasMore" class="feed-load-more">
            <button type="button" class="feed-chip" :disabled="loadingMore" @click="loadMore">
              {{ loadingMore ? t('common.loading') : t('moments.loadMore') }}
            </button>
          </div>
        </div>
      </div>

      <aside class="moments-atmosphere stagger-item" aria-label="companion spotlight">
        <AtmospherePanel
          :companion="sidebarCompanion"
          :characters="charactersStore.list"
          :quote="sidebarQuote"
          :eyebrow="t('home.atmosphereEyebrow')"
          :continue-label="t('home.atmosphereContinue')"
          :empty-title="t('home.atmosphereEmptyTitle')"
          :empty-desc="t('home.atmosphereEmptyDesc')"
          :explore-label="t('home.exploreSquare')"
          @chat="goToCharacterChat"
          @explore="$router.push('/app/character-square')"
        />

        <section v-if="activeInFeed.length > 1 && !filterCharId" class="moments-sidebar-section glass">
          <div class="moments-sidebar-section__head">
            <h4 class="moments-sidebar-section__title">{{ t('moments.activeCharacters') }}</h4>
          </div>
          <div class="moments-active-row">
            <button
              v-for="c in activeInFeed"
              :key="c.id"
              type="button"
              class="moments-active-chip"
              @click="setFilter(c.id)"
            >
              <CharacterAvatarImg :character="c" :alt="c.name" :icon-size="16" />
              <span>{{ c.name }}</span>
            </button>
          </div>
        </section>

        <section v-if="recentDiaries.length" class="moments-sidebar-section glass">
          <div class="moments-sidebar-section__head">
            <h4 class="moments-sidebar-section__title">{{ t('moments.recentDiary') }}</h4>
            <router-link to="/app/diary" class="moments-sidebar-section__link">
              {{ t('home.feedViewAll') }}
            </router-link>
          </div>
          <ul class="moments-diary-list">
            <li
              v-for="entry in recentDiaries"
              :key="entry.id"
              class="moments-diary-item"
              @click="$router.push('/app/diary')"
            >
              <div class="moments-diary-item__meta">
                <span class="moments-diary-item__name">{{ entry.characterName }}</span>
                <span class="moments-diary-item__time">{{ formatTime(entry.createdAt) }}</span>
              </div>
              <p class="moments-diary-item__text">{{ entry.title || entry.content }}</p>
            </li>
          </ul>
        </section>
      </aside>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, onActivated, onDeactivated, onUnmounted, reactive, ref, watch } from 'vue'
defineOptions({ name: 'MomentsPage' })
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  ChatDotRound,
  Loading,
  PictureRounded,
  Promotion,
  RefreshRight,
  User
} from '@element-plus/icons-vue'
import { useCharactersStore } from '@/stores/characters'
import { useConversationsStore } from '@/stores/conversations'
import { useUserStore } from '@/stores/user'
import { listAllDiaries, listCharacterStates } from '@/api/characterState'
import {
  addMomentComment,
  createMomentPost,
  fetchMomentComments,
  fetchMomentsFeed,
  markMomentsSeen
} from '@/api/moments'
import { resolveMediaUrl } from '@/utils/media'
import { sanitizeHtml } from '@/utils/sanitize'
import { feedDateKey, formatFeedDateLabel, formatFeedTime } from '@/utils/feedTime'
import { truncateText } from '@/utils/text'
import { useOpenSingleChat } from '@/composables/useOpenSingleChat'
import AtmospherePanel from '@/components/AtmospherePanel.vue'
import CharacterAvatarImg from '@/components/CharacterAvatarImg.vue'
import { buildMomentCommentThread, formatMomentCommentAuthorLabel } from './momentsCommentThread'

const { t, locale } = useI18n()
const route = useRoute()
const router = useRouter()

const charactersStore = useCharactersStore()
const conversationsStore = useConversationsStore()
const userStore = useUserStore()
const { openSingleChat } = useOpenSingleChat()
const posts = ref([])
const filterCharId = ref(null)
const loading = ref(false)
const loadingMore = ref(false)
const cursor = ref(null)
const hasMore = ref(false)
const emotionStates = ref([])
const recentDiaries = ref([])

const commentsByPost = ref({})
const commentsLoading = reactive({})
const commentsFullyExpanded = reactive({})
const draftByPost = reactive({})
const replyTarget = reactive({})
const sending = reactive({})
const expandedComments = reactive({})
const userPostDraft = ref('')
const userPostSending = ref(false)

let pollTimer = null

const feedTimeline = computed(() => {
  const items = []
  if (!posts.value.length) return items

  let idx = 0
  const first = posts.value[0]
  items.push({
    kind: 'post',
    post: first,
    isHero: true,
    key: `hero-${first.id}`,
    idx: idx++
  })

  let lastDateKey = feedDateKey(first.createdAt)
  for (const post of posts.value.slice(1)) {
    const dateKey = feedDateKey(post.createdAt)
    if (dateKey !== lastDateKey) {
      items.push({
        kind: 'divider',
        label: formatFeedDateLabel(post.createdAt, t),
        key: `date-${dateKey}`
      })
      lastDateKey = dateKey
    }
    items.push({
      kind: 'post',
      post,
      isHero: false,
      key: post.id,
      idx: idx++
    })
  }

  return items
})

const activeInFeed = computed(() => {
  const ids = new Set(posts.value.map(p => p.characterId).filter(Boolean))
  return charactersStore.list.filter(c => ids.has(c.id)).slice(0, 8)
})

const firstCharacterPost = computed(() =>
  posts.value.find(p => p.authorType === 'CHARACTER' && p.characterId)
)

const sidebarCompanion = computed(() => {
  if (filterCharId.value) {
    const char = charactersStore.list.find(c => c.id === filterCharId.value)
    const emotion = emotionStates.value.find(s => s.characterId === filterCharId.value)
    const latestPost = posts.value.find(
      p => p.characterId === filterCharId.value && p.authorType === 'CHARACTER'
    )
    if (!char && !latestPost && !emotion) return null
    return {
      characterId: filterCharId.value,
      name: char?.name || latestPost?.characterName || emotion?.characterName,
      avatarUrl: latestPost?.characterAvatarUrl || emotion?.avatarUrl || char?.avatarUrl || '',
      avatarThumbUrl: latestPost?.characterAvatarThumbUrl || emotion?.avatarThumbUrl || char?.avatarThumbUrl || '',
      emotion
    }
  }

  const post = firstCharacterPost.value
  if (post) {
    const emotion = emotionStates.value.find(s => s.characterId === post.characterId)
    return {
      characterId: post.characterId,
      name: post.characterName,
      avatarUrl: post.characterAvatarUrl || '',
      avatarThumbUrl: post.characterAvatarThumbUrl || '',
      emotion
    }
  }

  const state = emotionStates.value[0]
  if (state) {
    return {
      characterId: state.characterId,
      name: state.characterName,
      avatarUrl: state.avatarUrl || '',
      avatarThumbUrl: state.avatarThumbUrl || '',
      emotion: state
    }
  }

  return null
})

const sidebarQuote = computed(() => {
  if (filterCharId.value) {
    const post = posts.value.find(
      p => p.characterId === filterCharId.value && p.authorType === 'CHARACTER'
    )
    if (post?.content) return truncateText(post.content, 140)
    const emotion = emotionStates.value.find(s => s.characterId === filterCharId.value)
    if (emotion?.statusText) return emotion.statusText
  }

  const post = firstCharacterPost.value
  if (post?.content) return truncateText(post.content, 140)

  const emotion = sidebarCompanion.value?.emotion
  if (emotion?.statusText) return emotion.statusText

  return t('home.atmosphereFallbackQuote')
})

function applyRouteCharacterFilter() {
  const raw = route.query.characterId
  if (raw == null || raw === '') {
    filterCharId.value = null
    return
  }
  const id = Number(raw)
  filterCharId.value = Number.isFinite(id) ? id : null
}

onMounted(async () => {
  try {
    await charactersStore.fetchList()
    await userStore.fetchProfile()
  } catch {
    charactersStore.invalidate()
  }
  applyRouteCharacterFilter()
  await Promise.all([
    reloadFeed(),
    loadSidebarData()
  ])
  try {
    await markMomentsSeen()
  } catch {
    /* ignore */
  }
})

let firstActivation = true
onActivated(() => {
  startCommentPolling()
  if (firstActivation) { firstActivation = false; return }
  reloadFeed()
  loadSidebarData()
})

onDeactivated(() => {
  stopCommentPolling()
})

watch(
  () => route.query.characterId,
  () => {
    applyRouteCharacterFilter()
    reloadFeed()
  }
)

onUnmounted(() => {
  stopCommentPolling()
})

async function loadSidebarData() {
  await conversationsStore.fetchList().catch(() => [])
  try {
    const states = await listCharacterStates({ silent: true })
    emotionStates.value = Array.isArray(states) ? states : []
  } catch {
    emotionStates.value = []
  }
  try {
    const diaries = await listAllDiaries({ page: 1, size: 3 }, { silent: true })
    recentDiaries.value = Array.isArray(diaries) ? diaries.slice(0, 3) : []
  } catch {
    recentDiaries.value = []
  }
}

function startCommentPolling() {
  stopCommentPolling()
  pollTimer = setInterval(() => {
    for (const post of posts.value) {
      if (expandedComments[post.id]) {
        loadComments(post.id, true)
      }
    }
  }, 6000)
}

function stopCommentPolling() {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

function setFilter(id) {
  if (filterCharId.value === id) return
  filterCharId.value = id
  reloadFeed()
}

async function applyFeedPage(data, { append = false } = {}) {
  const items = data?.items || []
  if (append) {
    posts.value = [...posts.value, ...items]
  } else {
    posts.value = items
  }
  cursor.value = data?.nextCursor ?? null
  hasMore.value = !!data?.hasMore
  for (const post of items) {
    if (draftByPost[post.id] === undefined) draftByPost[post.id] = ''
    await loadComments(post.id, true)
  }
}

async function reloadFeed() {
  loading.value = true
  cursor.value = null
  posts.value = []
  commentsByPost.value = {}
  Object.keys(expandedComments).forEach(k => delete expandedComments[k])
  Object.keys(commentsFullyExpanded).forEach(k => delete commentsFullyExpanded[k])
  try {
    const data = await fetchMomentsFeed({
      characterId: filterCharId.value || undefined,
      limit: 20
    })
    await applyFeedPage(data)
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
    await applyFeedPage(data, { append: true })
  } finally {
    loadingMore.value = false
  }
}

async function loadComments(postId, silent = false) {
  if (!postId) return
  if (!silent) commentsLoading[postId] = true
  try {
    const all = []
    let cursorId = undefined
    let guard = 0
    while (guard < 20) {
      guard += 1
      const data = await fetchMomentComments(postId, {
        limit: 200,
        ...(cursorId ? { cursor: cursorId } : {})
      })
      const batch = data?.items || []
      all.push(...batch)
      if (!data?.hasMore || !data?.nextCursor || batch.length === 0) break
      cursorId = data.nextCursor
    }
    commentsByPost.value = {
      ...commentsByPost.value,
      [postId]: all
    }
  } catch {
    if (!silent) commentsByPost.value = { ...commentsByPost.value, [postId]: [] }
  } finally {
    if (!silent) commentsLoading[postId] = false
  }
}

function toggleCommentsFullyExpanded(postId) {
  commentsFullyExpanded[postId] = !commentsFullyExpanded[postId]
}

function getComments(postId) {
  return commentsByPost.value[postId] || []
}

function threadedComments(postId) {
  return buildMomentCommentThread(getComments(postId))
}

function formatCommentAuthorLabel(comment) {
  return formatMomentCommentAuthorLabel(
    comment,
    t('moments.you'),
    (author, target) => t('moments.replyAuthorLabel', { author, target })
  )
}

function commentCount(post) {
  const loaded = getComments(post.id).length
  const reported = post.commentCount ?? 0
  return Math.max(loaded, reported)
}

function commentActionLabel(post) {
  const n = commentCount(post)
  if (expandedComments[post.id]) return t('feed.hideComments')
  if (n > 0) return t('feed.commentCount', { n })
  return t('feed.addComment')
}

function commentStripHtml(post) {
  const comments = getComments(post.id)
  const count = commentCount(post)
  if (count === 0) return ''

  if (comments.length === 0) {
    return escapeHtml(t('feed.commentCount', { n: count }))
  }

  const latest = comments[comments.length - 1]
  const name = latest.characterName || t('moments.you')
  const text = latest.content.length > 56 ? `${latest.content.slice(0, 56)}…` : latest.content
  let html = `<strong>${escapeHtml(name)}</strong> ${escapeHtml(text)}`

  if (count > 1) {
    html += ` · ${escapeHtml(t('feed.moreComments', { n: count - 1 }))}`
  }
  return html
}

function escapeHtml(str) {
  return String(str)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
}

async function toggleComments(post, open = null) {
  const next = open ?? !expandedComments[post.id]
  expandedComments[post.id] = next
  if (next && !getComments(post.id).length) {
    await loadComments(post.id, false)
  }
}

function setReplyTarget(post, comment) {
  expandedComments[post.id] = true
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
    expandedComments[post.id] = true
    await loadComments(post.id, true)
    setTimeout(() => loadComments(post.id, true), 2500)
    setTimeout(() => loadComments(post.id, true), 6000)
  } catch {
    /* http interceptor */
  } finally {
    sending[post.id] = false
  }
}

function postAuthorName(post) {
  return post.authorType === 'USER' ? t('moments.you') : post.characterName
}

function userAuthorAvatar(post) {
  return post.userAvatarUrl || userStore.avatarUrl || null
}

async function submitUserPost() {
  const text = userPostDraft.value.trim()
  if (!text || userPostSending.value) return
  userPostSending.value = true
  try {
    await createMomentPost({ content: text })
    userPostDraft.value = ''
    ElMessage.success(t('moments.postSent'))
    await reloadFeed()
  } catch {
    /* http interceptor */
  } finally {
    userPostSending.value = false
  }
}

function postTypeClass(type) {
  const key = type?.toLowerCase()
  if (key === 'mood' || key === 'reflection' || key === 'system' || key === 'user') {
    return `feed-card--${key}`
  }
  return ''
}

function typeLabel(type) {
  const map = {
    MOOD: t('moments.typeMood'),
    REFLECTION: t('moments.typeReflection'),
    SYSTEM: t('moments.typeSystem'),
    USER: t('moments.typeUser')
  }
  return map[type] || type
}

function formatTime(iso) {
  return formatFeedTime(iso, t, locale.value)
}

function goChat(post) {
  if (!post.conversationId) return
  router.push(`/app/chat/${post.conversationId}`)
}

function goToCharacterChat(characterId) {
  void openSingleChat(characterId)
}
</script>

<style lang="scss" scoped>
.feed-compose-card {
  padding: $space-4;
  margin-bottom: $space-4;
  display: flex;
  flex-direction: column;
  gap: $space-3;

  &__head {
    display: flex;
    align-items: center;
    gap: $space-2;
  }

  &__avatar {
    flex-shrink: 0;
  }

  &__title {
    font-weight: 600;
    color: $color-text-primary;
  }

  &__actions {
    display: flex;
    justify-content: flex-end;
  }
}

.empty-icon {
  width: 72px;
  height: 72px;
  margin: 0 auto;
  border-radius: $radius-lg;
  background: rgba($color-pink-rgb, 0.1);
  display: flex;
  align-items: center;
  justify-content: center;
  color: $color-pink-primary;
}

.reply-hint {
  margin: 0 0 $space-2;
  font-size: $font-size-xs;
  color: $color-text-muted;
}

.feed-comment-zone {
  display: flex;
  flex-direction: column;
  gap: $space-2;
  margin-top: $space-3;
}

.feed-comment-scroll {
  max-height: 14rem;
  overflow-y: auto;
  padding-right: $space-1;
  transition: max-height 0.24s cubic-bezier(0.23, 1, 0.32, 1);

  &--expanded {
    max-height: none;
    overflow: visible;
  }
}

.feed-comment-list {
  display: flex;
  flex-direction: column;
  gap: $space-3;
  margin: 0;
  padding: 0;
  list-style: none;
}

.feed-comment-fold {
  align-self: flex-start;
  margin: 0;
  padding: $space-1 $space-3;
  border: none;
  border-radius: $radius-pill;
  background: rgba($color-pink-rgb, 0.1);
  color: $color-pink-primary;
  font-size: $font-size-xs;
  cursor: pointer;
  transition: background 0.2s cubic-bezier(0.23, 1, 0.32, 1);

  &:hover {
    background: rgba($color-pink-rgb, 0.18);
  }
}

.feed-comment-item {
  display: flex;
  flex-direction: column;
  gap: $space-2;

  &__row {
    display: flex;
    flex-wrap: wrap;
    gap: $space-2;
    line-height: 1.6;
  }

  &__name {
    font-weight: 600;
    color: $color-text-primary;
  }

  &__reply {
    align-self: flex-start;
  }
}

.feed-comment-reply-list {
  display: flex;
  flex-direction: column;
  gap: $space-2;
  margin: 0;
  padding: 0 0 0 $space-4;
  list-style: none;
}

.link-btn {
  margin-left: $space-2;
  padding: 0;
  border: none;
  background: none;
  color: $color-pink-primary;
  cursor: pointer;
  font-size: inherit;
}
</style>
