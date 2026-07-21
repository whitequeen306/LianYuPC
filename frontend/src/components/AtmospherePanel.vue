<template>
  <div
    v-if="companion"
    class="atmosphere-panel glass"
    @click="$emit('chat', companion.characterId)"
  >
    <div class="atmosphere-panel__glow" aria-hidden="true" />
    <div class="atmosphere-panel__orb atmosphere-panel__orb--a" aria-hidden="true" />
    <div class="atmosphere-panel__orb atmosphere-panel__orb--b" aria-hidden="true" />

    <div class="atmosphere-panel__portrait">
      <img
        v-if="portraitSrc"
        :src="resolveMediaUrl(portraitSrc)"
        :alt="companion.name"
        class="atmosphere-panel__img"
      />
      <div v-else class="atmosphere-panel__fallback">
        <el-icon :size="48"><User /></el-icon>
      </div>
      <div class="atmosphere-panel__vignette" aria-hidden="true" />
    </div>

    <div class="atmosphere-panel__body">
      <span class="atmosphere-panel__eyebrow">{{ eyebrow }}</span>
      <h3 class="atmosphere-panel__name">{{ companion.name }}</h3>
      <div v-if="companion.emotion" class="atmosphere-panel__mood">
        <EmotionBadge
          :current-emotion="companion.emotion.currentEmotion"
          :emotion-intensity="companion.emotion.emotionIntensity"
          :status-text="companion.emotion.statusText"
        />
      </div>
      <blockquote class="atmosphere-panel__quote">
        <p>{{ quote }}</p>
      </blockquote>
      <el-button type="primary" class="atmosphere-panel__cta" @click.stop="$emit('chat', companion.characterId)">
        {{ continueLabel }}
      </el-button>
    </div>
  </div>

  <div v-else class="atmosphere-panel atmosphere-panel--empty glass">
    <p class="atmosphere-panel__empty-title">{{ emptyTitle }}</p>
    <p class="atmosphere-panel__empty-desc">{{ emptyDesc }}</p>
    <el-button type="primary" class="atmosphere-panel__cta" @click="$emit('explore')">
      {{ exploreLabel }}
    </el-button>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { User } from '@element-plus/icons-vue'
import EmotionBadge from '@/components/EmotionBadge.vue'
import { resolveMediaUrl } from '@/utils/media'
import { pickCharacterAvatarRaw } from '@/utils/characterAvatar'

const props = defineProps({
  companion: { type: Object, default: null },
  quote: { type: String, default: '' },
  eyebrow: { type: String, required: true },
  continueLabel: { type: String, required: true },
  emptyTitle: { type: String, required: true },
  emptyDesc: { type: String, required: true },
  exploreLabel: { type: String, required: true }
})

defineEmits(['chat', 'explore'])

const portraitSrc = computed(() => pickCharacterAvatarRaw(props.companion, 'thumb'))
</script>
