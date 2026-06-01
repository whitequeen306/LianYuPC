<template>
  <aside class="app-sidebar" :class="{ collapsed: settingsStore.sidebarCollapsed }">
    <div class="sidebar-brand" @click="$router.push('/app')">
      <div class="brand-icon">
        <span class="brand-char">语</span>
      </div>
      <transition name="fade">
        <span v-show="!settingsStore.sidebarCollapsed" class="brand-text">LianYu</span>
      </transition>
    </div>

    <nav class="sidebar-nav">
      <router-link
        v-for="item in navItems"
        :key="item.path"
        :to="item.path"
        class="nav-item"
        :class="{ active: isActive(item.path) }"
      >
        <span class="nav-icon">
          <el-icon :size="22"><component :is="item.icon" /></el-icon>
        </span>
        <span class="nav-label">{{ item.label }}</span>
        <span v-if="isActive(item.path)" class="nav-indicator"></span>
      </router-link>
    </nav>

    <div class="sidebar-footer">
      <router-link to="/app/profile" class="nav-item" :class="{ active: isActive('/app/profile') }">
        <span class="nav-icon">
          <el-icon :size="20"><UserFilled /></el-icon>
        </span>
        <span class="nav-label">{{ t('nav.profile') }}</span>
      </router-link>
      <router-link to="/app/settings" class="nav-item" :class="{ active: isActive('/app/settings') }">
        <span class="nav-icon">
          <el-icon :size="20"><Setting /></el-icon>
        </span>
        <span class="nav-label">{{ t('nav.settings') }}</span>
      </router-link>
    </div>
  </aside>
</template>

<script setup>
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { useSettingsStore } from '@/stores/settings'
import { Setting, UserFilled } from '@element-plus/icons-vue'

const route = useRoute()
const settingsStore = useSettingsStore()
const { t } = useI18n()

const navItems = computed(() => [
  { path: '/app', label: t('nav.home'), icon: 'HomeFilled' },
  { path: '/app/character-square', label: t('nav.characterSquare'), icon: 'Grid' },
  { path: '/app/characters', label: t('nav.characters'), icon: 'User' },
  { path: '/app/group-chat', label: t('nav.groupChat'), icon: 'ChatDotRound' },
  { path: '/app/moments', label: t('nav.moments'), icon: 'PictureRounded' },
  { path: '/app/memory', label: t('nav.memory'), icon: 'Collection' }
])

function isActive(path) {
  if (path === '/app') return route.path === '/app'
  return route.path.startsWith(path)
}
</script>

<style lang="scss" scoped>
.app-sidebar {
  position: fixed;
  left: 0; top: 0; bottom: 0;
  width: $sidebar-width;
  display: flex;
  flex-direction: column;
  z-index: $z-sidebar;
  transition: width $transition-slow;
  background: $color-bg-secondary;
  border-right: 1px solid rgba($color-pink-rgb, 0.06);
  overflow: hidden;

  &.collapsed {
    width: $sidebar-collapsed;
    .nav-label, .brand-text, .nav-indicator { display: none; }
    .nav-item { justify-content: center; padding: $space-3; }
  }
}

// ---- Brand ----
.sidebar-brand {
  height: $header-height;
  display: flex;
  align-items: center;
  gap: $space-3;
  padding: 0 $space-5;
  cursor: pointer;
  border-bottom: 1px solid rgba($color-pink-rgb, 0.06);
  flex-shrink: 0;
}

.brand-icon {
  width: 38px; height: 38px;
  border-radius: $radius-md;
  background: linear-gradient(135deg, $color-pink-primary, $color-pink-dark);
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  box-shadow: 0 2px 12px rgba($color-pink-rgb, 0.25);
}

.brand-char {
  font-size: 1.2rem;
  font-weight: $font-weight-bold;
  color: $color-text-inverse;
}

.brand-text {
  font-size: $font-size-xl;
  font-weight: $font-weight-bold;
  color: $color-text-primary;
  letter-spacing: 0.04em;
  white-space: nowrap;
}

// ---- Navigation ----
.sidebar-nav {
  flex: 1;
  padding: $space-4 $space-3;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.sidebar-footer {
  padding: $space-3;
  border-top: 1px solid rgba($color-pink-rgb, 0.06);
}

// ---- Nav Item ----
.nav-item {
  position: relative;
  display: flex;
  align-items: center;
  gap: $space-3;
  padding: $space-3 $space-4;
  border-radius: $radius-md;
  color: $color-text-secondary;
  text-decoration: none;
  transition: all $transition-fast;
  white-space: nowrap;
  font-weight: $font-weight-medium;
  font-size: $font-size-sm;

  &:hover {
    color: $color-text-primary;
    background: rgba($color-pink-rgb, 0.1);
  }

  &.active {
    color: $color-pink-primary;
    background: rgba($color-pink-rgb, 0.12);
    font-weight: $font-weight-semibold;

    .nav-icon { color: $color-pink-primary; }
  }
}

.nav-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 24px; height: 24px;
  flex-shrink: 0;
  color: inherit;
  transition: color $transition-fast;
}

.nav-label {
  font-size: $font-size-sm;
  font-weight: inherit;
  letter-spacing: 0.03em;
}

.nav-indicator {
  position: absolute;
  left: 0;
  top: 8px; bottom: 8px;
  width: 3px;
  border-radius: 0 3px 3px 0;
  background: $color-pink-primary;
  box-shadow: 0 0 8px rgba($color-pink-rgb, 0.4);
}

.fade-enter-active, .fade-leave-active {
  transition: opacity 0.2s;
}
.fade-enter-from, .fade-leave-to { opacity: 0; }
</style>
