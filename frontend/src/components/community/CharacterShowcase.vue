<template>
  <section class="character-showcase">
    <h3 class="character-showcase__title">{{ title }}</h3>
    <p v-if="hidden" class="character-showcase__hidden">该用户隐藏了他的宝宝们哦~</p>
    <div v-else-if="!characters.length" class="character-showcase__empty">还没有角色</div>
    <div v-else class="character-showcase__grid">
      <button
        v-for="c in characters"
        :key="c.characterId"
        type="button"
        class="character-showcase__card glass"
        :class="{ 'is-selected': selectable && selectedId === c.characterId }"
        :disabled="!selectable"
        @click="onSelect(c.characterId)"
      >
        <div class="character-showcase__avatar">
          <CharacterAvatarImg
            :character-id="c.characterId"
            :avatar-url="c.avatarUrl || ''"
            :avatar-thumb-url="c.avatarThumbUrl || ''"
            :alt="c.name"
            :icon-size="22"
          />
        </div>
        <div class="character-showcase__meta">
          <span class="character-showcase__name">{{ c.name }}</span>
          <span class="character-showcase__days">已陪伴 {{ c.companionshipDays }} 天</span>
        </div>
      </button>
    </div>
  </section>
</template>

<script setup>
import CharacterAvatarImg from '@/components/CharacterAvatarImg.vue'

const props = defineProps({
  title: { type: String, default: '角色橱窗' },
  characters: { type: Array, default: () => [] },
  hidden: { type: Boolean, default: false },
  selectable: { type: Boolean, default: false },
  selectedId: { type: [Number, String], default: null }
})

const emit = defineEmits(['select'])

function onSelect(characterId) {
  if (!props.selectable || props.hidden) return
  emit('select', props.selectedId === characterId ? null : characterId)
}
</script>

<style lang="scss" scoped>
.character-showcase {
  display: flex;
  flex-direction: column;
  gap: $space-5;
}

.character-showcase__title {
  margin: 0;
  font-size: $font-size-xl;
  font-weight: $font-weight-semibold;
  letter-spacing: 0.01em;
  color: var(--ly-text-primary);
}

.character-showcase__hidden,
.character-showcase__empty {
  margin: 0;
  padding: $space-5 $space-6;
  border-radius: $radius-lg;
  background: color-mix(in srgb, var(--ly-accent) 5%, transparent);
  color: var(--ly-text-muted);
  font-size: $font-size-sm;
}

.character-showcase__grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
  gap: $space-4;
}

.character-showcase__card {
  display: flex;
  align-items: center;
  gap: $space-4;
  padding: $space-4;
  border-radius: $radius-lg;
  border: 1px solid transparent;
  background: none;
  color: inherit;
  text-align: left;
  cursor: default;
  transition:
    transform 0.24s cubic-bezier(0.23, 1, 0.32, 1),
    border-color 0.24s cubic-bezier(0.23, 1, 0.32, 1);

  &:not(:disabled) {
    cursor: pointer;
  }

  &:not(:disabled):hover {
    transform: translateY(-2px);
    border-color: color-mix(in srgb, var(--ly-accent) 22%, transparent);
  }

  &.is-selected {
    border-color: color-mix(in srgb, var(--ly-accent) 45%, transparent);
    box-shadow: $shadow-glow-pink;
  }
}

.character-showcase__avatar {
  width: 52px;
  height: 52px;
  border-radius: $radius-full;
  overflow: hidden;
  background: var(--ly-bg-elevated);
  display: grid;
  place-items: center;
  flex-shrink: 0;
  color: var(--ly-text-muted);

  :deep(img) {
    width: 100%;
    height: 100%;
    object-fit: cover;
  }
}

.character-showcase__meta {
  display: flex;
  flex-direction: column;
  gap: $space-1;
  min-width: 0;
}

.character-showcase__name {
  font-weight: $font-weight-semibold;
  color: var(--ly-text-primary);
  line-height: 1.2;
}

.character-showcase__days {
  font-size: $font-size-xs;
  color: var(--ly-text-muted);
  line-height: 1.2;
}
</style>
