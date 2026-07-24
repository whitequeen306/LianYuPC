<template>
  <div class="diary-page companion-page companion-feed stagger-container">
    <header class="companion-hero stagger-item">
      <span class="companion-eyebrow">{{ t('feed.eyebrow') }}</span>
      <h1 class="companion-title">{{ t('diary.title') }}</h1>
      <p class="companion-lead">{{ t('diary.desc') }}</p>
    </header>

    <div v-if="loading" class="feed-empty glass stagger-item">
      <el-icon class="is-loading" :size="28"><Loading /></el-icon>
      <p>{{ t('common.loading') }}</p>
    </div>

    <div v-else-if="visibleDiaries.length === 0" class="feed-empty glass stagger-item">
      <div class="empty-icon">
        <el-icon :size="40"><Notebook /></el-icon>
      </div>
      <h3>{{ t('diary.empty') }}</h3>
      <p>{{ t('diary.emptyDesc') }}</p>
    </div>

    <div v-else class="social-feed">
      <template v-for="group in groupedDiaries" :key="group.key">
        <div class="feed-date-divider">{{ group.label }}</div>
        <article
          v-for="(diary, idx) in group.items"
          :key="diary.id"
          class="feed-card glass stagger-item"
          :style="{ animationDelay: `${idx * 0.05}s` }"
        >
          <div class="feed-card__head">
            <div class="feed-card__avatar">
              <CharacterAvatarImg
                :character-id="diary.characterId"
                :avatar-url="diary.avatarUrl || ''"
                :avatar-thumb-url="diary.avatarThumbUrl || ''"
                :alt="diary.characterName"
                :icon-size="18"
              />
            </div>
            <div class="feed-card__meta">
              <span class="feed-card__name">{{ diary.characterName }}</span>
              <span class="feed-card__time">{{ formatFeedTime(diary.createdAt, t, locale) }}</span>
            </div>
            <span class="feed-card__badge feed-card__badge--diary">{{ t('diary.badge') }}</span>
          </div>

          <h3 v-if="diary.title" class="feed-card__title">{{ diary.title }}</h3>
          <p class="feed-card__body">{{ diary.content }}</p>
        </article>
      </template>
    </div>
  </div>
</template>

<script setup>
import { computed, ref, onMounted, onActivated, watch } from 'vue'
defineOptions({ name: 'DiaryPage' })
import { useI18n } from 'vue-i18n'
import { useRoute } from 'vue-router'
import { Loading, Notebook } from '@element-plus/icons-vue'
import { listAllDiaries } from '@/api/characterState'
import CharacterAvatarImg from '@/components/CharacterAvatarImg.vue'
import { feedDateKey, formatFeedDateLabel, formatFeedTime } from '@/utils/feedTime'

const { t, locale } = useI18n()
const route = useRoute()

const loading = ref(true)
const diaries = ref([])
const filterCharId = ref(null)

const visibleDiaries = computed(() => {
  if (!filterCharId.value) return diaries.value
  return diaries.value.filter((diary) => diary.characterId === filterCharId.value)
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

const groupedDiaries = computed(() => {
  const map = new Map()
  for (const diary of visibleDiaries.value) {
    const key = feedDateKey(diary.createdAt)
    if (!map.has(key)) {
      map.set(key, {
        key,
        label: formatFeedDateLabel(diary.createdAt, t),
        items: []
      })
    }
    map.get(key).items.push(diary)
  }
  return Array.from(map.values())
})

onMounted(async () => {
  applyRouteCharacterFilter()
  await loadDiaries()
})

async function loadDiaries() {
  try {
    const data = await listAllDiaries({ page: 1, size: 50 })
    diaries.value = Array.isArray(data) ? data : []
  } catch {
    /* errors handled by global interceptor */
  } finally {
    loading.value = false
  }
}

let firstActivation = true
onActivated(() => {
  if (firstActivation) { firstActivation = false; return }
  loadDiaries()
})

watch(
  () => route.query.characterId,
  () => {
    applyRouteCharacterFilter()
  }
)

</script>

<style lang="scss" scoped>
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
</style>
