<template>
  <el-popover
    placement="bottom-end"
    :width="300"
    trigger="click"
    popper-class="theme-color-popover"
    @show="$emit('open')"
  >
    <template #reference>
      <button class="theme-trigger" type="button" title="主题配色">
        <span class="theme-trigger__preview" aria-hidden="true">
          <span class="swatch-bg" :style="{ background: settingsStore.backgroundColor }" />
          <span class="swatch-accent" :style="{ background: settingsStore.accentColor }" />
        </span>
      </button>
    </template>

    <div class="theme-panel">
      <p class="theme-panel-title">主题配色</p>
      <p class="theme-panel-desc">背景偏冷 · 按钮偏暖，可分别调整</p>

      <p class="section-label">快捷组合</p>
      <div class="combo-row">
        <button
          v-for="preset in THEME_PRESETS"
          :key="preset.name"
          type="button"
          class="combo-chip"
          :class="{ active: isActiveCombo(preset) }"
          :title="preset.name"
          @click="settingsStore.applyThemePreset(preset.bg, preset.accent)"
        >
          <span class="chip-bg" :style="{ background: preset.bg }" />
          <span class="chip-accent" :style="{ background: preset.accent }" />
          <span class="chip-name">{{ preset.name }}</span>
        </button>
      </div>

      <div class="section-block">
        <p class="section-label">
          <span class="label-dot label-dot--cool" />背景 · 偏冷
        </p>
        <div class="preset-row">
          <button
            v-for="preset in BG_PRESETS"
            :key="preset.color"
            type="button"
            class="preset-dot preset-dot--cool"
            :class="{ active: isActiveBg(preset.color) }"
            :title="preset.name"
            :style="{ background: preset.color }"
            @click="settingsStore.setBackgroundColor(preset.color)"
          />
        </div>
        <div class="custom-row">
          <el-color-picker
            :model-value="settingsStore.backgroundColor"
            color-format="hex"
            :predefine="bgPredefine"
            @change="onBgChange"
          />
          <span class="custom-hex">{{ settingsStore.backgroundColor }}</span>
        </div>
      </div>

      <div class="section-block">
        <p class="section-label">
          <span class="label-dot label-dot--warm" />按钮 · 偏暖
        </p>
        <div class="preset-row">
          <button
            v-for="preset in ACCENT_PRESETS"
            :key="preset.color"
            type="button"
            class="preset-dot preset-dot--warm"
            :class="{ active: isActiveAccent(preset.color) }"
            :title="preset.name"
            :style="{ background: preset.color }"
            @click="settingsStore.setAccentColor(preset.color)"
          />
        </div>
        <div class="custom-row">
          <el-color-picker
            :model-value="settingsStore.accentColor"
            color-format="hex"
            :predefine="accentPredefine"
            @change="onAccentChange"
          />
          <span class="custom-hex">{{ settingsStore.accentColor }}</span>
        </div>
      </div>

      <el-button text size="small" class="btn-reset" @click="settingsStore.resetTheme">
        恢复默认
      </el-button>
    </div>
  </el-popover>
</template>

<script setup>
import { computed } from 'vue'
import { useSettingsStore } from '@/stores/settings'
import {
  THEME_PRESETS,
  BG_PRESETS,
  ACCENT_PRESETS,
  normalizeHex
} from '@/utils/themeColor'

const settingsStore = useSettingsStore()

defineEmits(['open'])

const bgPredefine = computed(() => BG_PRESETS.map(p => p.color))
const accentPredefine = computed(() => ACCENT_PRESETS.map(p => p.color))

function isActiveBg(color) {
  return normalizeHex(settingsStore.backgroundColor) === normalizeHex(color)
}

function isActiveAccent(color) {
  return normalizeHex(settingsStore.accentColor) === normalizeHex(color)
}

function isActiveCombo(preset) {
  return (
    isActiveBg(preset.bg) &&
    isActiveAccent(preset.accent)
  )
}

function onBgChange(val) {
  if (val) settingsStore.setBackgroundColor(val)
}

function onAccentChange(val) {
  if (val) settingsStore.setAccentColor(val)
}
</script>

<style lang="scss" scoped>
.theme-trigger {
  width: 40px;
  height: 36px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: $radius-md;
  color: $color-text-secondary;
  transition: all $transition-fast;
  position: relative;

  &:hover {
    color: var(--ly-accent);
    background: rgba(var(--ly-accent-rgb), 0.08);
  }
}

.theme-trigger__preview {
  display: flex;
  width: 24px;
  height: 20px;
  border-radius: 5px;
  overflow: hidden;
  border: 1px solid rgba(255, 255, 255, 0.22);
  pointer-events: none;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.18);
}

.swatch-bg {
  flex: 1;
  min-width: 0;
  pointer-events: none;
}

.swatch-accent {
  width: 10px;
  flex-shrink: 0;
  pointer-events: none;
  box-shadow: inset 1px 0 0 rgba(255, 255, 255, 0.12);
}

.theme-panel-title {
  font-size: $font-size-sm;
  font-weight: $font-weight-semibold;
  color: $color-text-primary;
  margin-bottom: $space-1;
}

.theme-panel-desc {
  font-size: $font-size-xs;
  color: $color-text-muted;
  margin-bottom: $space-4;
}

.section-label {
  display: flex;
  align-items: center;
  gap: $space-2;
  font-size: $font-size-xs;
  font-weight: $font-weight-medium;
  color: $color-text-secondary;
  margin-bottom: $space-2;
}

.label-dot {
  width: 8px;
  height: 8px;
  border-radius: $radius-full;
  flex-shrink: 0;

  &--cool { background: #7a9ec4; }
  &--warm { background: #f4a6b5; }
}

.section-block {
  margin-bottom: $space-4;
}

.combo-row {
  display: flex;
  flex-wrap: wrap;
  gap: $space-2;
  margin-bottom: $space-4;
}

.combo-chip {
  display: flex;
  align-items: center;
  gap: 0;
  padding: 4px 8px 4px 4px;
  border-radius: $radius-pill;
  background: rgba(var(--ly-bg-surface-rgb), 0.6);
  border: 1px solid rgba(var(--ly-accent-rgb), 0.1);
  cursor: pointer;
  transition: all $transition-fast;

  &:hover {
    border-color: rgba(var(--ly-accent-rgb), 0.25);
  }

  &.active {
    border-color: var(--ly-accent);
    box-shadow: 0 0 0 1px rgba(var(--ly-accent-rgb), 0.2);
  }
}

.chip-bg,
.chip-accent {
  width: 14px;
  height: 14px;
  display: inline-block;
}

.chip-bg {
  border-radius: 4px 0 0 4px;
}

.chip-accent {
  border-radius: 0 4px 4px 0;
  margin-right: $space-2;
}

.chip-name {
  font-size: 11px;
  color: $color-text-secondary;
  white-space: nowrap;
}

.preset-row {
  display: flex;
  flex-wrap: wrap;
  gap: $space-2;
  margin-bottom: $space-3;
}

.preset-dot {
  width: 26px;
  height: 26px;
  border-radius: $radius-full;
  border: 2px solid transparent;
  cursor: pointer;
  transition: transform $transition-fast, box-shadow $transition-fast;

  &:hover { transform: scale(1.08); }

  &.active {
    border-color: $color-text-primary;
  }

  &--cool.active {
    box-shadow: 0 0 0 2px rgba(122, 158, 196, 0.45);
  }

  &--warm.active {
    box-shadow: 0 0 0 2px rgba(var(--ly-accent-rgb), 0.45);
  }
}

.custom-row {
  display: flex;
  align-items: center;
  gap: $space-3;
}

.custom-hex {
  font-size: $font-size-xs;
  font-family: $font-mono;
  color: $color-text-muted;
  letter-spacing: 0.04em;
}

.btn-reset {
  color: $color-text-muted !important;
  padding: 0 !important;

  &:hover {
    color: var(--ly-accent) !important;
  }
}
</style>

<style lang="scss">
.theme-color-popover.el-popover.el-popper {
  background: $color-bg-surface !important;
  border: 1px solid rgba(var(--ly-accent-rgb), 0.12) !important;
  border-radius: $radius-lg !important;
  box-shadow: $shadow-lg !important;
}
</style>
