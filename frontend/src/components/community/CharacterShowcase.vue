<template>
  <section class="character-showcase">
    <h3 class="character-showcase__title">{{ title }}</h3>
    <p v-if="hidden" class="character-showcase__hidden">该用户隐藏了他的宝宝们哦~</p>
    <div v-else-if="!characters.length" class="character-showcase__empty">还没有角色</div>
    <div v-else class="character-showcase__grid">
      <article v-for="c in characters" :key="c.characterId" class="character-showcase__card glass">
        <div class="character-showcase__avatar">
          <img v-if="c.avatarUrl" :src="resolveMediaUrl(c.avatarUrl)" :alt="c.name" />
          <el-icon v-else :size="22"><User /></el-icon>
        </div>
        <div class="character-showcase__meta">
          <span class="character-showcase__name">{{ c.name }}</span>
          <span class="character-showcase__days">已陪伴 {{ c.companionshipDays }} 天</span>
        </div>
      </article>
    </div>
  </section>
</template>

<script setup>
import { User } from '@element-plus/icons-vue'
import { resolveMediaUrl } from '@/utils/media'

defineProps({
  title: { type: String, default: '角色橱窗' },
  characters: { type: Array, default: () => [] },
  hidden: { type: Boolean, default: false }
})
</script>

<style lang="scss" scoped>
.character-showcase {
  display: flex;
  flex-direction: column;
  gap: $space-3;
}

.character-showcase__title {
  margin: 0;
  font-size: $font-size-lg;
  font-weight: $font-weight-semibold;
  color: var(--ly-text-primary);
}

.character-showcase__hidden,
.character-showcase__empty {
  margin: 0;
  color: var(--ly-text-muted);
  font-size: $font-size-sm;
}

.character-showcase__grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(160px, 1fr));
  gap: $space-3;
}

.character-showcase__card {
  display: flex;
  align-items: center;
  gap: $space-3;
  padding: $space-3;
  border-radius: $radius-lg;
}

.character-showcase__avatar {
  width: 48px;
  height: 48px;
  border-radius: 9999px;
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

.character-showcase__meta {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.character-showcase__name {
  font-weight: $font-weight-medium;
  color: var(--ly-text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.character-showcase__days {
  font-size: $font-size-sm;
  color: var(--ly-text-muted);
}
</style>
