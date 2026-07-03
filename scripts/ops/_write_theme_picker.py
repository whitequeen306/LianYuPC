from pathlib import Path

CONTENT = r'''<template>
  <el-popover
    placement="bottom-end"
    :width="280"
    trigger="click"
    popper-class="theme-color-popover"
    @show="$emit('open')"
  >
    <template #reference>
      <button class="theme-trigger" type="button" title="主题配色">
        <span class="theme-trigger__preview" aria-hidden="true">
          <span class="swatch-mode" :class="{ 'is-light': settingsStore.theme === 'light' }" />
          <span class="swatch-accent" :style="{ background: settingsStore.accentColor }" />
        </span>
      </button>
    </template>

    <div class="theme-panel">
      <p class="theme-panel-title">主题配色</p>
      <p class="theme-panel-desc">切换外观模式 · 调整强调色</p>

      <div class="mode-segment">
        <button
          type="button"
          class="mode-segment__btn"
          :class="{ active: settingsStore.theme === 'dark' }"
          @click="settingsStore.setAppearanceMode('dark')"
        >
          <el-icon :size="16"><Moon /></el-icon>
          <span>深色</span>
        </button>
        <button
          type="button"
          class="mode-segment__btn"
          :class="{ active: settingsStore.theme === 'light' }"
          @click="settingsStore.setAppearanceMode('light')"
        >
          <el-icon :size="16"><Sunny /></el-icon>
          <span>浅色</span>
        </button>
      </div>

      <div class="section-block">
        <p class="section-label">
          <span class="label-dot label-dot--warm" />强调色
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

      <el-button text size="small" class="btn-reset" @click="settingsStore.resetAppearance">
        恢复默认
      </el-button>
    </div>
  </el-popover>
</template>

<script setup>
import { computed } from 'vue'
import { Moon, Sunny } from '@element-plus/icons-vue'
import { useSettingsStore } from '@/stores/settings'
import { ACCENT_PRESETS, normalizeHex } from '@/utils/themeColor'

const settingsStore = useSettingsStore()

defineEmits(['open'])

const accentPredefine = computed(() => ACCENT_PRESETS.map(p => p.color))

function isActiveAccent(color) {
  return normalizeHex(settingsStore.accentColor) === normalizeHex(color)
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

.swatch-mode {
  flex: 1;
  min-width: 0;
  background: #121820;
  pointer-events: none;

  &.is-light {
    background: #f7f7f9;
  }
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

.mode-segment {
  display: flex;
  padding: 3px;
  margin-bottom: $space-4;
  border-radius: $radius-md;
  background: rgba(var(--ly-bg-surface-rgb), 0.5);
  border: 1px solid rgba(var(--ly-accent-rgb), 0.08);
  g ap: 2px;
}

.mode-segment__btn {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  g ap: $space-2;
  padding: 6px 8px;
  border-radius: calc(#{$radius-md} - 2px);
  font-size: $font-size-xs;
  color: $color-text-secondary;
  transition: all $transition-fast;

  &:hover {
    color: $color-text-primary;
  }

  &.active {
    color: $color-text-primary;
    background: rgba(var(--ly-accent-rgb), 0.12);
    box-shadow: 0 1px 4px rgba(0, 0, 0, 0.08);
  }
}

.section-label {
  display: flex;
  align-items: center;
  g ap: $space-2;
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

  &--warm { background: #f4a6b5; }
}

.section-block {
  margin-bottom: $space-4;
}

.preset-row {
  display: flex;
  flex-wrap: wrap;
  g ap: $space-2;
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

  &--warm.active {
    box-shadow: 0 0 0 2px rgba(var(--ly-accent-rgb), 0.45);
  }
}

.custom-row {
  display: flex;
  align-items: center;
  g ap: $space-3;
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
'''

CONTENT = CONTENT.replace('g ap:', 'gap:')

dest = Path(__file__).resolve().parents[1] / 'frontend' / 'src' / 'components' / ' ThemeColorPicker.vue'.strip()
dest.write_text(CONTENT,encoding='utf-8')
print('wrote', dest)
