<template>
  <div class="home-page companion-page stagger-container">
    <header class="companion-hero stagger-item">
      <span class="companion-eyebrow">{{ t('home.eyebrow') }}</span>
      <h1 class="companion-title">{{ greeting }}</h1>
      <p class="companion-lead">{{ t('home.subtitle') }}</p>
    </header>

    <div class="moments-layout">
      <div class="moments-main">
        <section class="companion-section stagger-item">
          <div class="companion-section__head">
            <h2 class="companion-section__title">{{ t('home.feedSection') }}</h2>
            <router-link to="/app/moments" class="companion-section__link">{{ t('home.feedViewAll') }}</router-link>
          </div>

          <div v-if="feedLoading" class="feed-empty glass">
            <el-icon class="is-loading" :size="22"><Loading /></el-icon>
          </div>

          <p v-else-if="!feedPreview.length" class="feed-empty-hint">{{ t('home.feedEmpty') }}</p>

          <div v-else class="social-feed">
            <article
              v-for="item in feedPreview"
              :key="item.key"
              v-tilt
              class="feed-preview-card glass"
              @click="openFeedItem(item)"
            >
              <div class="feed-card__head">
                <div class="feed-card__avatar">
                  <img v-if="item.avatarUrl" :src="resolveMediaUrl(item.avatarUrl)" :alt="item.characterName" />
                  <el-icon v-else :size="16"><User /></el-icon>
                </div>
                <div class="feed-card__meta">
                  <span class="feed-card__name">{{ item.characterName }}</span>
                  <span class="feed-card__time">{{ formatFeedItemTime(item.createdAt) }}</span>
                </div>
                <span class="feed-card__badge" :class="item.kind === 'diary' ? 'feed-card__badge--diary' : ''">
                  {{ item.kind === 'diary' ? t('feed.typeDiary') : t('feed.typeMoment') }}
                </span>
              </div>
              <h3 v-if="item.title" class="feed-card__title">{{ item.title }}</h3>
              <p class="feed-card__body">{{ item.content }}</p>
            </article>
          </div>
        </section>

        <section v-if="emotionStates.length" class="companion-section stagger-item">
          <div class="companion-section__head">
            <h2 class="companion-section__title">{{ t('home.moodSection') }}</h2>
          </div>
          <div class="companion-chip-row">
            <div
              v-for="state in emotionStates"
              :key="state.characterId"
              class="companion-portrait-card"
              @click="goToChat(state.characterId)"
            >
              <div class="portrait-ring">
                <img
                  v-if="state.avatarUrl"
                  :src="resolveMediaUrl(state.avatarUrl)"
                  class="portrait-img"
                  :alt="state.characterName"
                />
                <div v-else class="portrait-fallback">
                  <el-icon :size="28"><User /></el-icon>
                </div>
              </div>
              <div class="portrait-name">{{ state.characterName }}</div>
              <div class="portrait-meta">
                <EmotionBadge
                  :current-emotion="state.currentEmotion"
                  :emotion-intensity="state.emotionIntensity"
                  :status-text="state.statusText"
                />
              </div>
            </div>
          </div>
        </section>

        <section class="companion-section stagger-item">
          <div class="companion-story-card">
            <h2>{{ t('home.welcomeTitle') }}</h2>
            <p>{{ t('home.welcomeDesc') }}</p>
            <div class="story-actions">
              <el-button v-bubble-btn type="primary" class="btn-cta" @click="$router.push('/app/characters')">
                {{ t('home.goCharacters') }}
              </el-button>
              <el-button v-bubble-btn class="btn-ghost" @click="$router.push('/app/character-square')">
                {{ t('home.exploreSquare') }}
              </el-button>
            </div>
          </div>
        </section>

        <section class="companion-section stagger-item">
          <div class="companion-section__head">
            <h2 class="companion-section__title">{{ t('home.discoverSection') }}</h2>
          </div>
          <div class="discover-grid">
            <router-link v-tilt to="/app/diary" class="discover-tile glass">
              <el-icon :size="22"><Notebook /></el-icon>
              <span>{{ t('nav.diary') }}</span>
            </router-link>
            <router-link v-tilt to="/app/moments" class="discover-tile glass">
              <el-icon :size="22"><PictureRounded /></el-icon>
              <span>{{ t('nav.moments') }}</span>
            </router-link>
            <router-link v-tilt to="/app/group-chat" class="discover-tile glass">
              <el-icon :size="22"><ChatDotRound /></el-icon>
              <span>{{ t('nav.groupChat') }}</span>
            </router-link>
            <router-link v-tilt to="/app/memory" class="discover-tile glass">
              <el-icon :size="22"><Collection /></el-icon>
              <span>{{ t('nav.memory') }}</span>
            </router-link>
          </div>
        </section>
      </div>

      <aside class="moments-atmosphere stagger-item" aria-label="companion spotlight">
        <AtmospherePanel
          :companion="featuredCompanion"
          :quote="atmosphereQuote"
          :eyebrow="t('home.atmosphereEyebrow')"
          :continue-label="t('home.atmosphereContinue')"
          :empty-title="t('home.atmosphereEmptyTitle')"
          :empty-desc="t('home.atmosphereEmptyDesc')"
          :explore-label="t('home.exploreSquare')"
          @chat="goToChat"
          @explore="$router.push('/app/character-square')"
        />
      </aside>
    </div>
  </div>
</template>

<script setup>
import { computed, ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { useUserStore } from '@/stores/user'
import {
  ChatDotRound,
  Loading,
  User,
  Notebook,
  PictureRounded,
  Collection
} from '@element-plus/icons-vue'
import { useCharactersStore } from '@/stores/characters'
import { useConversationsStore } from '@/stores/conversations'
import { listAllDiaries, listCharacterStates } from '@/api/characterState'
import { fetchMomentsFeed } from '@/api/moments'
import { resolveMediaUrl } from '@/utils/media'
import { formatFeedTime, parseFeedDateTime } from '@/utils/feedTime'
import { truncateText } from '@/utils/text'
import { useOpenSingleChat } from '@/composables/useOpenSingleChat'
import AtmospherePanel from '@/components/AtmospherePanel.vue'
import EmotionBadge from '@/components/EmotionBadge.vue'

const { t, locale } = useI18n()
const router = useRouter()
const userStore = useUserStore()
const charactersStore = useCharactersStore()
const conversationsStore = useConversationsStore()
const { openSingleChat } = useOpenSingleChat()
const emotionStates = ref([])
const feedPreview = ref([])
const feedLoading = ref(true)

const greeting = computed(() => {
  const hour = new Date().getHours()
  const name = userStore.displayName
  if (hour < 6) return t('home.greetingNight', { name })
  if (hour < 12) return t('home.greetingMorning', { name })
  if (hour < 18) return t('home.greetingAfternoon', { name })
  return t('home.greetingEvening', { name })
})

const featuredCompanion = computed(() => {
  const latestCharacterMoment = feedPreview.value.find(
    item => item.kind === 'moment' && item.characterId
  )
  if (latestCharacterMoment) {
    const emotion = emotionStates.value.find(s => s.characterId === latestCharacterMoment.characterId)
    return {
      characterId: latestCharacterMoment.characterId,
      name: latestCharacterMoment.characterName,
      avatarUrl: latestCharacterMoment.avatarUrl,
      emotion
    }
  }

  const singles = (conversationsStore.list || []).filter(c => c.mode === 'SINGLE' && c.characterId)
  if (singles.length) {
    const conv = singles[0]
    const emotion = emotionStates.value.find(s => s.characterId === conv.characterId)
    return {
      characterId: conv.characterId,
      name: conv.characterName || conv.title,
      avatarUrl: conv.characterAvatarUrl,
      emotion
    }
  }

  const state = emotionStates.value[0]
  if (state) {
    const char = charactersStore.list.find(c => c.id === state.characterId)
    return {
      characterId: state.characterId,
      name: state.characterName,
      avatarUrl: state.avatarUrl || char?.avatarUrl,
      emotion: state
    }
  }

  return null
})

const atmosphereQuote = computed(() => {
  const latestCharacterMoment = feedPreview.value.find(
    item => item.kind === 'moment' && item.characterId && item.content
  )
  if (latestCharacterMoment?.content) {
    return truncateText(latestCharacterMoment.content, 140)
  }
  const emotion = featuredCompanion.value?.emotion
  if (emotion?.statusText) {
    return emotion.statusText
  }
  return t('home.atmosphereFallbackQuote')
})

onMounted(async () => {
  await charactersStore.fetchList().catch(() => [])
  await conversationsStore.fetchList().catch(() => [])
  try {
    const states = await listCharacterStates({ silent: true })
    emotionStates.value = Array.isArray(states) ? states : []
  } catch {
    emotionStates.value = []
  }
  await loadFeedPreview()
})

async function loadFeedPreview() {
  feedLoading.value = true
  try {
    const [momentsRes, diariesRes] = await Promise.all([
      fetchMomentsFeed({ limit: 5 }, { silent: true }).catch(() => ({ items: [] })),
      listAllDiaries({ page: 1, size: 5 }, { silent: true }).catch(() => [])
    ])
    const moments = (momentsRes?.items || []).map(p => ({
      key: `m-${p.id}`,
      kind: 'moment',
      id: p.id,
      authorType: p.authorType,
      characterId: p.characterId,
      characterName: p.authorType === 'USER' ? t('moments.you') : p.characterName,
      avatarUrl: p.authorType === 'USER'
        ? (p.userAvatarUrl || userStore.avatarUrl)
        : p.characterAvatarUrl,
      title: '',
      content: p.content,
      createdAt: p.createdAt,
      route: '/app/moments'
    }))
    const diaries = (Array.isArray(diariesRes) ? diariesRes : []).map(d => ({
      key: `d-${d.id}`,
      kind: 'diary',
      id: d.id,
      characterId: d.characterId,
      characterName: d.characterName,
      avatarUrl: d.avatarUrl,
      title: d.title,
      content: d.content,
      createdAt: d.createdAt,
      route: '/app/diary'
    }))
    feedPreview.value = [...moments, ...diaries]
      .sort((a, b) => {
        const ta = parseFeedDateTime(a.createdAt)?.getTime() ?? 0
        const tb = parseFeedDateTime(b.createdAt)?.getTime() ?? 0
        return tb - ta
      })
      .slice(0, 4)
  } finally {
    feedLoading.value = false
  }
}

function formatFeedItemTime(iso) {
  return formatFeedTime(iso, t, locale.value)
}

function openFeedItem(item) {
  router.push(item.route)
}

function goToChat(characterId) {
  void openSingleChat(characterId)
}
</script>

<style lang="scss" scoped>
.home-page {
  width: 100%;
}

.story-actions {
  position: relative;
  display: flex;
  flex-wrap: wrap;
  gap: $space-3;
}

.btn-ghost {
  border-radius: $radius-pill;
  padding: 0 $space-5;
  height: 40px;
  color: $color-text-secondary;
  border: 1px solid rgba($color-pink-rgb, 0.15);
  background: rgba(var(--ly-bg-surface-rgb), 0.35);

  &:hover {
    color: $color-pink-primary;
    border-color: rgba($color-pink-rgb, 0.35);
  }
}

.discover-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: $space-3;
}

.discover-tile {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: $space-3;
  padding: $space-5;
  border-radius: $radius-lg;
  color: $color-text-secondary;
  font-size: $font-size-sm;
  letter-spacing: 0.04em;
  transition: transform $transition-fast, border-color $transition-fast;

  &:hover {
    transform: translateY(-2px);
    color: $color-pink-primary;
    border-color: rgba($color-pink-rgb, 0.2);
  }
}

.feed-empty-hint {
  margin: 0;
  padding: $space-4 $space-5;
  font-size: $font-size-sm;
  color: $color-text-muted;
  line-height: $line-height-relaxed;
}
</style>
