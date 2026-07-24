<template>
  <img
    v-if="displaySrc"
    :src="displaySrc"
    :alt="alt"
    :class="imgClass"
    :loading="loading"
    :decoding="decoding"
    @error="onError"
  >
  <el-icon v-else :size="iconSize" :class="fallbackClass">
    <User />
  </el-icon>
</template>

<script setup>
/**
 * Unified character avatar: thumb → orig → User icon.
 * Prefer this over raw resolveMediaUrl(avatarUrl) so call sites don't diverge.
 */
import { computed, ref, watch } from 'vue'
import { User } from '@element-plus/icons-vue'
import { resolveMediaUrl } from '@/utils/media'
import {
  nextCharacterAvatarTier,
  resolveCharacterAvatarSrc,
} from '@/utils/characterAvatar'

const props = defineProps({
  /** Full character / member object (uses .id / .avatarUrl / .avatarThumbUrl) */
  character: { type: Object, default: null },
  characterId: { type: [Number, String], default: null },
  characters: { type: Array, default: () => [] },
  avatarUrl: { type: String, default: '' },
  avatarThumbUrl: { type: String, default: '' },
  /** DTO aliases */
  characterAvatarUrl: { type: String, default: '' },
  characterAvatarThumbUrl: { type: String, default: '' },
  alt: { type: String, default: '' },
  iconSize: { type: [Number, String], default: 18 },
  imgClass: { type: [String, Object, Array], default: '' },
  fallbackClass: { type: [String, Object, Array], default: '' },
  loading: { type: String, default: 'lazy' },
  decoding: { type: String, default: 'async' },
})

const tier = ref('thumb')

const sourceKey = computed(() => [
  props.character?.id,
  props.characterId,
  props.character?.avatarUrl,
  props.character?.avatarThumbUrl,
  props.avatarUrl,
  props.avatarThumbUrl,
  props.characterAvatarUrl,
  props.characterAvatarThumbUrl,
].join('|'))

watch(sourceKey, () => {
  tier.value = 'thumb'
}, { flush: 'sync' })

const mergedCharacter = computed(() => {
  const idHint = props.characterId ?? props.character?.id ?? props.character?.characterId
  const fromList = idHint != null && Array.isArray(props.characters)
    ? props.characters.find((c) => c != null && (c.id === idHint || c.characterId === idHint))
    : null
  // Prefer explicit props / character fields, then fill gaps from characters list.
  return {
    id: props.character?.id ?? props.character?.characterId ?? fromList?.id ?? idHint,
    avatarUrl: props.avatarUrl
      || props.characterAvatarUrl
      || props.character?.avatarUrl
      || fromList?.avatarUrl
      || '',
    avatarThumbUrl: props.avatarThumbUrl
      || props.characterAvatarThumbUrl
      || props.character?.avatarThumbUrl
      || fromList?.avatarThumbUrl
      || '',
  }
})

const rawSrc = computed(() => resolveCharacterAvatarSrc({
  character: mergedCharacter.value,
  characterId: mergedCharacter.value.id,
  avatarUrl: mergedCharacter.value.avatarUrl,
  avatarThumbUrl: mergedCharacter.value.avatarThumbUrl,
  tier: tier.value,
}))

const displaySrc = computed(() => {
  const raw = rawSrc.value
  return raw ? resolveMediaUrl(raw) : ''
})

function onError() {
  const next = nextCharacterAvatarTier(mergedCharacter.value, tier.value)
  tier.value = next
}
</script>
