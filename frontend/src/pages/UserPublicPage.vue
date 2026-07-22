<template>
  <div class="user-public-page stagger-container">
    <header class="page-header stagger-item">
      <div>
        <h1 class="page-title">{{ profile?.nickname || '用户主页' }}</h1>
        <p class="page-desc">社区动态与角色橱窗</p>
      </div>
    </header>

    <section v-if="profile" class="profile-hero glass stagger-item">
      <div class="profile-hero__avatar">
        <img v-if="profile.avatarUrl" :src="resolveMediaUrl(profile.avatarUrl)" alt="" />
        <el-icon v-else :size="36"><UserFilled /></el-icon>
      </div>
      <div class="profile-hero__meta">
        <span class="profile-hero__eyebrow">社区用户</span>
        <h2>{{ profile.nickname }}</h2>
      </div>
    </section>

    <CharacterShowcase
      class="stagger-item"
      :characters="profile?.characters || []"
      :hidden="!!profile?.charactersHidden"
    />

    <ProfilePostList
      class="stagger-item"
      title="社区动态"
      :items="posts"
      :loading="loading"
      :loading-more="loadingMore"
      :has-more="hasMore"
      empty-text="还没有社区动态"
      @load-more="loadMore"
    >
      <template #default="{ items }">
        <CommunityPostCard
          v-for="post in items"
          :key="post.id"
          :post="post"
          :show-comments="openCommentsId === post.id"
          @like="onLike"
          @toggle-comments="toggleComments"
        />
      </template>
    </ProfilePostList>
  </div>
</template>

<script setup>
import { onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { UserFilled } from '@element-plus/icons-vue'
import CharacterShowcase from '@/components/community/CharacterShowcase.vue'
import ProfilePostList from '@/components/community/ProfilePostList.vue'
import CommunityPostCard from '@/components/community/CommunityPostCard.vue'
import { fetchPublicUserProfile, fetchUserCommunityPosts } from '@/api/users'
import { toggleCommunityLike } from '@/api/community'
import { resolveMediaUrl } from '@/utils/media'
import { useUserStore } from '@/stores/user'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const profile = ref(null)
const posts = ref([])
const cursor = ref(null)
const hasMore = ref(false)
const loading = ref(false)
const loadingMore = ref(false)
const openCommentsId = ref(null)

watch(() => route.params.userId, () => {
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
  loading.value = true
  try {
    profile.value = await fetchPublicUserProfile(userId)
    const data = await fetchUserCommunityPosts(userId, { limit: 5 })
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
    const userId = Number(route.params.userId)
    const data = await fetchUserCommunityPosts(userId, { cursor: cursor.value, limit: 10 })
    posts.value = [...posts.value, ...(data?.items || [])]
    cursor.value = data?.nextCursor || null
    hasMore.value = !!data?.hasMore
  } finally {
    loadingMore.value = false
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
