<template>
  <div class="home-page stagger-container">
    <header class="page-hero stagger-item">
      <h1 class="hero-greeting">{{ greeting }}</h1>
      <p class="hero-sub">{{ t('home.subtitle') }}</p>
    </header>

    <div class="welcome-card glass stagger-item">
      <div class="welcome-icon">
        <el-icon :size="32"><ChatDotRound /></el-icon>
      </div>
      <div class="welcome-body">
        <h2>{{ t('home.welcomeTitle') }}</h2>
        <p>{{ t('home.welcomeDesc') }}</p>
        <el-button type="primary" class="btn-cta" @click="$router.push('/app/characters')">
          {{ t('home.goCharacters') }}
          <el-icon class="el-icon--right"><ArrowRight /></el-icon>
        </el-button>
      </div>
    </div>

    <div class="stats-row stagger-item">
      <div class="stat-card glass">
        <span class="stat-number">{{ characterCount }}</span>
        <span class="stat-label">{{ t('home.statCharacters') }}</span>
      </div>
      <div class="stat-card glass">
        <span class="stat-number">{{ conversationCount }}</span>
        <span class="stat-label">{{ t('home.statConversations') }}</span>
      </div>
      <div class="stat-card glass">
        <span class="stat-number">—</span>
        <span class="stat-label">{{ t('home.statTodayMessages') }}</span>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, ref, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { useUserStore } from '@/stores/user'

const { t } = useI18n()
import { ChatDotRound, ArrowRight } from '@element-plus/icons-vue'
import { listCharacters } from '@/api/character'
import { listConversations } from '@/api/conversation'

const userStore = useUserStore()
const characterCount = ref(0)
const conversationCount = ref(0)

const greeting = computed(() => {
  const hour = new Date().getHours()
  const name = userStore.displayName
  if (hour < 6) return t('home.greetingNight', { name })
  if (hour < 12) return t('home.greetingMorning', { name })
  if (hour < 18) return t('home.greetingAfternoon', { name })
  return t('home.greetingEvening', { name })
})

onMounted(async () => {
  try {
    const chars = await listCharacters()
    characterCount.value = Array.isArray(chars) ? chars.length : 0
  } catch {}
  try {
    const convs = await listConversations()
    conversationCount.value = Array.isArray(convs) ? convs.length : 0
  } catch {}
})
</script>

<style lang="scss" scoped>
.home-page {
  max-width: 720px;
}

.page-hero {
  margin-bottom: $space-10;
}

.hero-greeting {
  font-size: $font-size-2xl;
  font-weight: $font-weight-bold;
  color: $color-text-primary;
  margin-bottom: $space-2;
}

.hero-sub {
  color: $color-text-muted;
  font-size: $font-size-base;
}

.welcome-card {
  border-radius: $radius-lg;
  padding: $space-8;
  display: flex;
  gap: $space-6;
  align-items: center;
  margin-bottom: $space-8;
}

.welcome-icon {
  width: 64px; height: 64px;
  border-radius: $radius-lg;
  background: rgba($color-pink-rgb, 0.1);
  display: flex;
  align-items: center;
  justify-content: center;
  color: $color-pink-primary;
  flex-shrink: 0;
}

.welcome-body h2 {
  font-size: $font-size-lg;
  font-weight: $font-weight-semibold;
  color: $color-text-primary;
  margin-bottom: $space-2;
}

.welcome-body p {
  color: $color-text-muted;
  font-size: $font-size-sm;
  margin-bottom: $space-4;
}

.stats-row {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: $space-4;
}

.stat-card {
  border-radius: $radius-lg;
  padding: $space-5 $space-6;
  text-align: center;
}

.stat-number {
  display: block;
  font-size: $font-size-2xl;
  font-weight: $font-weight-bold;
  color: $color-pink-primary;
  margin-bottom: $space-1;
}

.stat-label {
  font-size: $font-size-xs;
  color: $color-text-muted;
  text-transform: uppercase;
  letter-spacing: 0.05em;
}
</style>
