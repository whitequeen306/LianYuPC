<template>
  <div class="user-public-page stagger-container">
    <header class="page-header stagger-item">
      <div>
        <h1 class="page-title">{{ profile?.nickname || '用户主页' }}</h1>
        <p class="page-desc">角色橱窗</p>
      </div>
    </header>

    <section v-if="profile" class="profile-hero glass stagger-item">
      <div class="profile-hero__avatar">
        <img v-if="profile.avatarUrl" :src="resolveMediaUrl(profile.avatarUrl)" alt="" />
        <el-icon v-else :size="36"><UserFilled /></el-icon>
      </div>
      <div class="profile-hero__meta">
        <span class="profile-hero__eyebrow">用户</span>
        <h2>{{ profile.nickname }}</h2>
      </div>
    </section>

    <CharacterShowcase
      class="stagger-item"
      :characters="profile?.characters || []"
      :hidden="!!profile?.charactersHidden"
      :selectable="!profile?.charactersHidden"
      :selected-id="selectedCharacterId"
      @select="onShowcaseSelect"
    />

  </div>
</template>

<script setup>
import { onMounted, ref, watch, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { UserFilled } from '@element-plus/icons-vue'
import CharacterShowcase from '@/components/CharacterShowcase.vue'
import { fetchPublicUserProfile } from '@/api/users'
import { resolveMediaUrl } from '@/utils/media'
import { useUserStore } from '@/stores/user'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const profile = ref(null)
const selectedCharacterId = ref(null)

// 橱窗选中高亮（社区下线后不再按角色筛选动态，仅保留视觉选中态）
function onShowcaseSelect(characterId) {
  selectedCharacterId.value = selectedCharacterId.value === characterId ? null : characterId
}

watch(() => route.params.userId, () => {
  selectedCharacterId.value = null
  loadAll()
})

onMounted(loadAll)

async function loadAll() {
  const userId = Number(route.params.userId)
  if (!userId) return
  if (userId === userStore.userId) {
    await router.replace('/app/profile')
    return
  }
  selectedCharacterId.value = null
  loading.value = true
  try {
    profile.value = await fetchPublicUserProfile(userId)
  } catch (e) {
    ElMessage.error(e?.message || '加载失败')
  } finally {
    loading.value = false
  }
}





</script>

<style lang="scss" scoped>
.user-public-page {
  display: flex;
  flex-direction: column;
  gap: $space-8;
  max-width: 880px;
}

.profile-hero {
  display: flex;
  align-items: center;
  gap: $space-5;
  padding: $space-6;
  border-radius: $radius-xl;
}

.profile-hero__meta {
  display: flex;
  flex-direction: column;
  gap: $space-2;
  min-width: 0;

  h2 {
    margin: 0;
    font-size: $font-size-2xl;
    font-weight: $font-weight-semibold;
    letter-spacing: 0.01em;
    color: var(--ly-text-primary);
  }
}

.profile-hero__eyebrow {
  font-size: $font-size-xs;
  font-weight: $font-weight-medium;
  letter-spacing: 0.16em;
  color: $color-pink-primary;
  opacity: 0.9;
}

.profile-hero__avatar {
  width: 84px;
  height: 84px;
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
</style>
