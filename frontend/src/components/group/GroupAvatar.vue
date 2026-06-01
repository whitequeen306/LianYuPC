<template>
  <div class="group-avatar" :class="`group-avatar--${layout}`" :style="{ width: sizePx, height: sizePx }">
    <template v-if="displayMembers.length === 0">
      <div class="group-avatar__placeholder">
        <el-icon :size="iconSize"><User /></el-icon>
      </div>
    </template>
    <template v-else-if="layout === 'single'">
      <div class="group-avatar__cell group-avatar__cell--full">
        <img v-if="displayMembers[0].avatarUrl" :src="resolveMediaUrl(displayMembers[0].avatarUrl)" :alt="displayMembers[0].name" />
        <el-icon v-else :size="iconSize"><User /></el-icon>
      </div>
    </template>
    <template v-else-if="layout === 'double'">
      <div
        v-for="(m, i) in displayMembers.slice(0, 2)"
        :key="m.id || i"
        class="group-avatar__cell"
        :class="i === 0 ? 'group-avatar__cell--left' : 'group-avatar__cell--right'"
      >
        <img v-if="m.avatarUrl" :src="resolveMediaUrl(m.avatarUrl)" :alt="m.name" />
        <el-icon v-else :size="smallIconSize"><User /></el-icon>
      </div>
    </template>
    <template v-else-if="layout === 'triple'">
      <div class="group-avatar__cell group-avatar__cell--triple-main">
        <img v-if="displayMembers[0].avatarUrl" :src="resolveMediaUrl(displayMembers[0].avatarUrl)" :alt="displayMembers[0].name" />
        <el-icon v-else :size="smallIconSize"><User /></el-icon>
      </div>
      <div class="group-avatar__triple-side">
        <div
          v-for="(m, i) in displayMembers.slice(1, 3)"
          :key="m.id || i"
          class="group-avatar__cell group-avatar__cell--triple-sub"
        >
          <img v-if="m.avatarUrl" :src="resolveMediaUrl(m.avatarUrl)" :alt="m.name" />
          <el-icon v-else :size="tinyIconSize"><User /></el-icon>
        </div>
      </div>
    </template>
    <template v-else-if="layout === 'quad'">
      <div
        v-for="(m, i) in displayMembers.slice(0, 4)"
        :key="m.id || i"
        class="group-avatar__cell group-avatar__cell--quad"
      >
        <img v-if="m.avatarUrl" :src="resolveMediaUrl(m.avatarUrl)" :alt="m.name" />
        <el-icon v-else :size="tinyIconSize"><User /></el-icon>
      </div>
    </template>
    <template v-else>
      <div
        v-for="(m, i) in displayMembers.slice(0, 9)"
        :key="m.id || i"
        class="group-avatar__cell group-avatar__cell--grid"
      >
        <img v-if="m.avatarUrl" :src="resolveMediaUrl(m.avatarUrl)" :alt="m.name" />
        <el-icon v-else :size="tinyIconSize"><User /></el-icon>
      </div>
    </template>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { User } from '@element-plus/icons-vue'
import { resolveMediaUrl } from '@/utils/media'

const props = defineProps({
  members: {
    type: Array,
    default: () => []
  },
  size: {
    type: Number,
    default: 64
  }
})

const sizePx = computed(() => `${props.size}px`)
const iconSize = computed(() => Math.round(props.size * 0.42))
const smallIconSize = computed(() => Math.round(props.size * 0.32))
const tinyIconSize = computed(() => Math.round(props.size * 0.26))

const displayMembers = computed(() => (props.members || []).filter(Boolean))

const layout = computed(() => {
  const n = displayMembers.value.length
  if (n <= 0) return 'empty'
  if (n === 1) return 'single'
  if (n === 2) return 'double'
  if (n === 3) return 'triple'
  if (n === 4) return 'quad'
  return 'grid'
})
</script>

<style lang="scss" scoped>
.group-avatar {
  position: relative;
  flex-shrink: 0;
  border-radius: $radius-lg;
  overflow: hidden;
  background: rgba($color-pink-rgb, 0.08);
  border: 1px solid rgba($color-pink-rgb, 0.12);
}

.group-avatar__placeholder {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  color: $color-pink-primary;
}

.group-avatar__cell {
  overflow: hidden;
  background: rgba($color-bg-surface, 0.6);
  display: flex;
  align-items: center;
  justify-content: center;
  color: $color-pink-primary;

  img {
    width: 100%;
    height: 100%;
    object-fit: cover;
    display: block;
  }
}

.group-avatar--single .group-avatar__cell--full {
  width: 100%;
  height: 100%;
}

.group-avatar--double {
  display: flex;
  width: 100%;
  height: 100%;
  gap: 1px;

  .group-avatar__cell--left,
  .group-avatar__cell--right {
    flex: 1;
    height: 100%;
  }
}

.group-avatar--triple {
  display: flex;
  width: 100%;
  height: 100%;
  gap: 1px;

  .group-avatar__cell--triple-main {
    flex: 1;
    height: 100%;
  }

  .group-avatar__triple-side {
    flex: 1;
    display: flex;
    flex-direction: column;
    gap: 1px;
    height: 100%;
  }

  .group-avatar__cell--triple-sub {
    flex: 1;
    width: 100%;
  }
}

.group-avatar--quad {
  display: grid;
  grid-template-columns: 1fr 1fr;
  grid-template-rows: 1fr 1fr;
  gap: 1px;
  width: 100%;
  height: 100%;

  .group-avatar__cell--quad {
    width: 100%;
    height: 100%;
  }
}

.group-avatar--grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  grid-template-rows: repeat(3, 1fr);
  gap: 1px;
  width: 100%;
  height: 100%;

  .group-avatar__cell--grid {
    width: 100%;
    height: 100%;
    min-height: 0;
  }
}
</style>
