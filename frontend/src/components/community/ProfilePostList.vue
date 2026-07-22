<template>
  <section class="profile-post-list">
    <header class="profile-post-list__head">
      <h3>{{ title }}</h3>
      <p v-if="desc" class="profile-post-list__desc">{{ desc }}</p>
    </header>

    <div v-if="loading && items.length === 0" class="profile-post-list__empty glass">加载中…</div>
    <div v-else-if="!items.length" class="profile-post-list__empty glass">{{ emptyText }}</div>

    <div v-else class="profile-post-list__stack">
      <slot :items="visibleItems" />
    </div>

    <div v-if="items.length > defaultVisible || hasMore" class="profile-post-list__more">
      <el-button
        v-if="!expanded && items.length > defaultVisible"
        text
        type="primary"
        @click="expanded = true"
      >
        展开更多
      </el-button>
      <el-button
        v-else-if="hasMore"
        text
        type="primary"
        :loading="loadingMore"
        @click="$emit('load-more')"
      >
        加载更多
      </el-button>
      <el-button
        v-if="expanded && items.length > defaultVisible"
        text
        @click="expanded = false"
      >
        收起
      </el-button>
    </div>
  </section>
</template>

<script setup>
import { computed, ref } from 'vue'

const props = defineProps({
  title: { type: String, required: true },
  desc: { type: String, default: '' },
  items: { type: Array, default: () => [] },
  loading: { type: Boolean, default: false },
  loadingMore: { type: Boolean, default: false },
  hasMore: { type: Boolean, default: false },
  emptyText: { type: String, default: '还没有内容' },
  defaultVisible: { type: Number, default: 5 }
})

defineEmits(['load-more'])

const expanded = ref(false)

const visibleItems = computed(() => {
  if (expanded.value || props.items.length <= props.defaultVisible) {
    return props.items
  }
  return props.items.slice(0, props.defaultVisible)
})
</script>

<style lang="scss" scoped>
.profile-post-list {
  display: flex;
  flex-direction: column;
  gap: $space-3;
}

.profile-post-list__head h3 {
  margin: 0;
  font-size: $font-size-lg;
  color: var(--ly-text-primary);
}

.profile-post-list__desc {
  margin: $space-1 0 0;
  color: var(--ly-text-muted);
  font-size: $font-size-sm;
}

.profile-post-list__empty {
  padding: $space-5;
  border-radius: $radius-lg;
  color: var(--ly-text-muted);
  text-align: center;
}

.profile-post-list__stack {
  display: flex;
  flex-direction: column;
  gap: $space-3;
}

.profile-post-list__more {
  display: flex;
  gap: $space-2;
}
</style>
