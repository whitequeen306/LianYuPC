<template>
  <el-popover
    placement="bottom-end"
    :width="280"
    trigger="click"
    popper-class="language-picker-popover"
  >
    <template #reference>
      <button class="lang-trigger" type="button" :title="t('language.pickerTitle')">
        <span class="lang-badge">{{ currentUiShort }}</span>
        <el-icon :size="16"><Operation /></el-icon>
      </button>
    </template>

    <div class="lang-panel">
      <p class="lang-panel-title">{{ t('language.pickerTitle') }}</p>

      <p class="section-label">{{ t('language.ui') }}</p>
      <div class="lang-row">
        <button
          v-for="code in SUPPORTED_LANGUAGES"
          :key="'ui-' + code"
          type="button"
          class="lang-chip"
          :class="{ active: settingsStore.uiLanguage === code }"
          @click="settingsStore.setUiLanguage(code)"
        >
          {{ t(`language.${code}`) }}
        </button>
      </div>

      <p class="section-label">{{ t('language.model') }}</p>
      <el-checkbox
        v-model="syncModelWithUi"
        class="sync-check"
        @change="onSyncChange"
      >
        {{ t('language.syncModel') }}
      </el-checkbox>
      <div class="lang-row" :class="{ disabled: syncModelWithUi }">
        <button
          v-for="code in SUPPORTED_LANGUAGES"
          :key="'model-' + code"
          type="button"
          class="lang-chip"
          :class="{ active: settingsStore.modelOutputLanguage === code }"
          :disabled="syncModelWithUi"
          @click="settingsStore.setModelOutputLanguage(code)"
        >
          {{ t(`language.${code}`) }}
        </button>
      </div>
    </div>
  </el-popover>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { Operation } from '@element-plus/icons-vue'
import { useSettingsStore } from '@/stores/settings'
import { SUPPORTED_LANGUAGES } from '@/constants/language'

const { t } = useI18n()
const settingsStore = useSettingsStore()

const syncModelWithUi = ref(settingsStore.modelOutputLanguage === settingsStore.uiLanguage)

const currentUiShort = computed(() => {
  const map = { zh: '中', ja: '日', en: 'EN' }
  return map[settingsStore.uiLanguage] || '中'
})

watch(
  () => settingsStore.uiLanguage,
  () => {
    if (syncModelWithUi.value) {
      settingsStore.setModelOutputLanguage(settingsStore.uiLanguage)
    }
  }
)

function onSyncChange(checked) {
  if (checked) {
    settingsStore.setModelOutputLanguage(settingsStore.uiLanguage)
  }
}

watch(
  () => settingsStore.modelOutputLanguage,
  val => {
    if (val === settingsStore.uiLanguage) {
      syncModelWithUi.value = true
    }
  }
)
</script>

<style lang="scss" scoped>
.lang-trigger {
  width: 40px;
  height: 36px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 2px;
  border-radius: $radius-md;
  color: $color-text-secondary;
  transition: all $transition-fast;
  position: relative;

  &:hover {
    color: var(--ly-accent);
    background: rgba(var(--ly-accent-rgb), 0.08);
  }
}

.lang-badge {
  font-size: 11px;
  font-weight: $font-weight-bold;
  line-height: 1;
  color: var(--ly-accent);
}

.lang-panel-title {
  font-size: $font-size-sm;
  font-weight: $font-weight-semibold;
  color: $color-text-primary;
  margin-bottom: $space-4;
}

.section-label {
  font-size: $font-size-xs;
  font-weight: $font-weight-medium;
  color: $color-text-secondary;
  margin-bottom: $space-2;
}

.lang-row {
  display: flex;
  flex-wrap: wrap;
  gap: $space-2;
  margin-bottom: $space-4;

  &.disabled {
    opacity: 0.55;
    pointer-events: none;
  }
}

.lang-chip {
  padding: 6px 12px;
  border-radius: $radius-pill;
  font-size: $font-size-xs;
  color: $color-text-secondary;
  background: rgba(var(--ly-bg-surface-rgb), 0.6);
  border: 1px solid rgba(var(--ly-accent-rgb), 0.12);
  cursor: pointer;
  transition: all $transition-fast;

  &:hover:not(:disabled) {
    border-color: rgba(var(--ly-accent-rgb), 0.3);
    color: $color-text-primary;
  }

  &.active {
    color: var(--ly-accent);
    border-color: var(--ly-accent);
    background: rgba(var(--ly-accent-rgb), 0.12);
  }

  &:disabled {
    cursor: not-allowed;
  }
}

.sync-check {
  margin-bottom: $space-2;
}
</style>

<style lang="scss">
.language-picker-popover.el-popover.el-popper {
  background: $color-bg-surface !important;
  border: 1px solid rgba(var(--ly-accent-rgb), 0.12) !important;
  border-radius: $radius-lg !important;
  box-shadow: $shadow-lg !important;
}
</style>
