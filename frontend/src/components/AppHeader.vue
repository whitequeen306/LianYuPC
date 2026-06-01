<template>
  <header class="app-header glass-strong">
    <div class="header-left">
      <button class="menu-toggle" @click="settingsStore.toggleSidebar()">
        <el-icon :size="20"><Fold v-if="!settingsStore.sidebarCollapsed" /><Expand v-else /></el-icon>
      </button>
      <span class="page-title">{{ currentTitle }}</span>
    </div>
    <div class="header-right">
      <LanguagePicker />
      <ThemeColorPicker />
      <el-popover placement="bottom" :width="320" trigger="click">
        <template #reference>
          <button class="btn-notify" :title="t('header.notifications')">
            <el-badge :value="notificationsStore.unreadCount" :hidden="!notificationsStore.unreadCount" :max="99">
              <el-icon :size="18"><Bell /></el-icon>
            </el-badge>
          </button>
        </template>
        <div class="notify-pop">
          <div class="notify-top">
            <span>{{ t('header.notifications') }}</span>
            <el-button text size="small" @click="notificationsStore.markAllRead">{{ t('header.markAllRead') }}</el-button>
          </div>
          <div class="notify-list">
            <div
              v-for="n in notificationsStore.latest.slice(0, 10)"
              :key="n.id"
              class="notify-item"
              :class="{ unread: !n.read }"
              @click="goConversation(n.conversationId)"
            >
              <div class="notify-title">{{ n.title || t('header.newMessage') }}</div>
              <div class="notify-body">{{ n.contentPreview || '' }}</div>
            </div>
            <div v-if="notificationsStore.latest.length === 0" class="notify-empty">{{ t('header.noNotifications') }}</div>
          </div>
          <div class="notify-actions">
            <el-button
              size="small"
              :disabled="notificationsStore.browserNotifyPermission === 'granted'"
              @click="notificationsStore.requestBrowserNotificationPermission()"
            >
              {{ notificationsStore.browserNotifyPermission === 'granted' ? t('header.browserNotifyOn') : t('header.enableBrowserNotify') }}
            </el-button>
            <el-button size="small" type="primary" @click="togglePush">
              {{ notificationsStore.pushEnabled ? t('header.pushOff') : t('header.pushOn') }}
            </el-button>
          </div>
        </div>
      </el-popover>
      <button class="user-info" @click="router.push('/app/profile')" :title="t('header.profile')">
        <div class="user-avatar">
          <img v-if="userStore.avatarUrl" :src="userStore.avatarUrl" alt="avatar" />
          <el-icon v-else :size="18"><UserFilled /></el-icon>
        </div>
        <span class="user-name">{{ userStore.displayName }}</span>
      </button>
      <button class="btn-logout" @click="handleLogout" :title="t('header.logout')">
        <el-icon :size="18"><SwitchButton /></el-icon>
      </button>
    </div>
  </header>
</template>

<script setup>
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useUserStore } from '@/stores/user'
import { useSettingsStore } from '@/stores/settings'
import { useNotificationsStore } from '@/stores/notifications'
import ThemeColorPicker from '@/components/ThemeColorPicker.vue'
import LanguagePicker from '@/components/LanguagePicker.vue'
import { useI18n } from 'vue-i18n'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const settingsStore = useSettingsStore()
const notificationsStore = useNotificationsStore()
const { t } = useI18n()

const currentTitle = computed(() => {
  if (route.meta?.titleKey) {
    return t(route.meta.titleKey)
  }
  return 'LianYu'
})

async function handleLogout() {
  notificationsStore.dispose()
  await userStore.logout()
  router.push('/login')
}

function goConversation(conversationId) {
  if (!conversationId) return
  notificationsStore.markConversationRead(conversationId)
  router.push(`/app/chat/${conversationId}`)
}

async function togglePush() {
  if (notificationsStore.pushEnabled) {
    await notificationsStore.disablePush()
    return
  }
  await notificationsStore.enablePush()
}
</script>

<style lang="scss" scoped>
.app-header {
  height: $header-height;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 $space-6;
  position: sticky;
  top: 0;
  z-index: $z-header;
  border-bottom: 1px solid rgba($color-pink-rgb, 0.06);
}

.header-left {
  display: flex;
  align-items: center;
  gap: $space-4;
}

.menu-toggle {
  width: 36px; height: 36px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: $radius-md;
  color: $color-text-secondary;
  transition: all $transition-fast;

  &:hover {
    color: $color-pink-primary;
    background: rgba($color-pink-rgb, 0.08);
  }
}

.page-title {
  font-size: $font-size-lg;
  font-weight: $font-weight-semibold;
  color: $color-text-primary;
  letter-spacing: 0.02em;
}

.header-right {
  display: flex;
  align-items: center;
  gap: $space-4;
}

.btn-notify {
  width: 36px; height: 36px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: $radius-md;
  color: $color-text-muted;
  transition: all $transition-fast;

  &:hover {
    color: $color-pink-primary;
    background: rgba($color-pink-rgb, 0.08);
  }
}

.notify-pop {
  .notify-top {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-bottom: $space-2;
    color: $color-text-primary;
    font-weight: $font-weight-medium;
  }

  .notify-list {
    max-height: 280px;
    overflow-y: auto;
    display: flex;
    flex-direction: column;
    gap: $space-2;
  }

  .notify-item {
    border-radius: $radius-md;
    padding: $space-2 $space-3;
    background: rgba($color-bg-secondary, 0.45);
    cursor: pointer;
    border: 1px solid transparent;

    &.unread {
      border-color: rgba($color-pink-rgb, 0.22);
    }

    &:hover {
      background: rgba($color-pink-rgb, 0.08);
    }
  }

  .notify-title {
    font-size: $font-size-sm;
    color: $color-text-primary;
    margin-bottom: 2px;
  }

  .notify-body {
    font-size: $font-size-xs;
    color: $color-text-muted;
    line-height: 1.5;
  }

  .notify-empty {
    text-align: center;
    color: $color-text-muted;
    padding: $space-4 0;
    font-size: $font-size-sm;
  }

  .notify-actions {
    margin-top: $space-3;
    display: flex;
    gap: $space-2;
    justify-content: flex-end;
  }
}

.user-info {
  display: flex;
  align-items: center;
  gap: $space-2;
  border-radius: $radius-pill;
  padding: 4px 10px 4px 4px;
  transition: background $transition-fast;

  &:hover {
    background: rgba($color-pink-rgb, 0.08);
  }
}

.user-avatar {
  width: 32px; height: 32px;
  border-radius: $radius-full;
  background: linear-gradient(135deg, $color-pink-primary, $color-pink-dark);
  display: flex;
  align-items: center;
  justify-content: center;
  color: $color-text-inverse;
  overflow: hidden;

  img {
    width: 100%;
    height: 100%;
    object-fit: cover;
  }
}

.user-name {
  font-size: $font-size-sm;
  color: $color-text-secondary;
  font-weight: $font-weight-medium;
}

.btn-logout {
  width: 36px; height: 36px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: $radius-md;
  color: $color-text-muted;
  transition: all $transition-fast;

  &:hover {
    color: $color-error;
    background: rgba($color-error, 0.08);
  }
}
</style>
