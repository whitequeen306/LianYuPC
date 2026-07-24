<template>
  <div class="group-avatar" :class="`group-avatar--${layout}`" :style="{ width: sizePx, height: sizePx }">
    <template v-if="displayMembers.length === 0">
      <div class="group-avatar__placeholder">
        <el-icon :size="iconSize"><User /></el-icon>
      </div>
    </template>
    <template v-else-if="layout === 'single'">
      <div class="group-avatar__cell group-avatar__cell--full">
        <CharacterAvatarImg :character="displayMembers[0]" :alt="displayMembers[0].name" :icon-size="iconSize" />
      </div>
    </template>
    <template v-else-if="layout === 'double'">
      <div
        v-for="(m, i) in displayMembers.slice(0, 2)"
        :key="m.id || i"
        class="group-avatar__cell"
        :class="i === 0 ? 'group-avatar__cell--left' : 'group-avatar__cell--right'"
      >
        <CharacterAvatarImg :character="m" :alt="m.name" :icon-size="smallIconSize" />
      </div>
    </template>
    <template v-else-if="layout === 'triple'">
      <div class="group-avatar__cell group-avatar__cell--triple-main">
        <CharacterAvatarImg :character="displayMembers[0]" :alt="displayMembers[0].name" :icon-size="smallIconSize" />
      </div>
      <div class="group-avatar__triple-side">
        <div
          v-for="(m, i) in displayMembers.slice(1, 3)"
          :key="m.id || i"
          class="group-avatar__cell group-avatar__cell--triple-sub"
        >
          <CharacterAvatarImg :character="m" :alt="m.name" :icon-size="tinyIconSize" />
        </div>
      </div>
    </template>
    <template v-else-if="layout === 'quad'">
      <div
        v-for="(m, i) in displayMembers.slice(0, 4)"
        :key="m.id || i"
        class="group-avatar__cell group-avatar__cell--quad"
      >
        <CharacterAvatarImg :character="m" :alt="m.name" :icon-size="tinyIconSize" />
      </div>
    </template>
    <template v-else>
      <div
        v-for="(m, i) in displayMembers.slice(0, 9)"
        :key="m.id || i"
        class="group-avatar__cell group-avatar__cell--grid"
      >
        <CharacterAvatarImg :character="m" :alt="m.name" :icon-size="tinyIconSize" />
      </div>
    </template>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { User } from '@element-plus/icons-vue'
import CharacterAvatarImg from '@/components/CharacterAvatarImg.vue'

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
  overflow: hidden;
  border-radius: $radius-md;
  background: var(--ly-bg-surface, #1e2732);
}

.group-avatar__placeholder {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--ly-text-muted);
}

.group-avatar__cell {
  overflow: hidden;
  background: var(--ly-bg-elevated, #252f3c);
  display: grid;
  place-items: center;

  :deep(img) {
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

  .group-avatar__cell--left,
  .group-avatar__cell--right {
    width: 50%;
    height: 100%;
  }
}

.group-avatar--triple {
  display: flex;

  .group-avatar__cell--triple-main {
    width: 50%;
    height: 100%;
  }

  .group-avatar__triple-side {
    width: 50%;
    height: 100%;
    display: flex;
    flex-direction: column;
  }

  .group-avatar__cell--triple-sub {
    width: 100%;
    height: 50%;
  }
}

.group-avatar--quad {
  display: grid;
  grid-template-columns: 1fr 1fr;
  grid-template-rows: 1fr 1fr;

  .group-avatar__cell--quad {
    width: 100%;
    height: 100%;
  }
}

.group-avatar--grid {
  display: grid;
  grid-template-columns: 1fr 1fr 1fr;
  grid-template-rows: 1fr 1fr 1fr;

  .group-avatar__cell--grid {
    width: 100%;
    height: 100%;
  }
}
</style>
