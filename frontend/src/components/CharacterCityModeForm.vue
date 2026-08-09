<template>
  <div class="city-mode-form">
    <el-form-item :label="t('cityMode.realCityLabel')" :prop="cityProp">
      <el-input
        v-model="cityModel"
        :placeholder="t('cityMode.realCityPlaceholder')"
        maxlength="50"
        show-word-limit
      />
      <div class="field-hint">{{ t('cityMode.realCityHint') }}</div>
    </el-form-item>
  </div>
</template>

<script setup>
import { computed, watch } from 'vue'
import { useI18n } from 'vue-i18n'

const props = defineProps({
  cityMode: {
    type: String,
    default: 'real'
  },
  city: {
    type: String,
    default: ''
  },
  cityProp: {
    type: String,
    default: 'city'
  }
})

const emit = defineEmits(['update:cityMode', 'update:city'])

const { t } = useI18n()

const cityModel = computed({
  get: () => props.city,
  set: (value) => emit('update:city', value)
})

// 虚构城市已下线：始终同步为现实城市模式
watch(
  () => props.cityMode,
  (mode) => {
    if (mode !== 'real') emit('update:cityMode', 'real')
  },
  { immediate: true }
)
</script>

<style lang="scss" scoped>
.field-hint {
  margin-top: $space-2;
  font-size: $font-size-xs;
  color: $color-text-muted;
  line-height: $line-height-relaxed;
}
</style>
