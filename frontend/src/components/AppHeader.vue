<template>
  <header class="app-header">
    <router-link to="/app" class="header-brand">
      <img :src="APP_LOGO" alt="LianYu" class="header-logo" />
      <span class="header-wordmark">LianYu</span>
    </router-link>

    <div class="header-actions">
      <LanguagePicker />
      <div class="header-guide-cluster">
        <div class="theme-entry">
          <ThemeColorPicker @open="dismissThemeHint" />
        </div>
        <div class="notify-entry">
          <el-popover placement="bottom-end" :width="320" trigger="click" @show="dismissPushHint">
            <template #reference>
              <button class="header-btn" :title="t('header.notifications')">
                <el-badge :value="notificationsStore.unreadCount" :hidden="!notificationsStore.unreadCount" :max="99">
                  <el-icon :size="20"><Bell /></el-icon>
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
        </div>

        <div v-if="showThemeHint || showPushHint" class="header-hints-stack">
          <transition name="theme-hint-fade">
            <OnboardingHintBubble
              v-if="showThemeHint"
              placement="header-item"
              arrow="left"
              :close-label="t('common.cancel')"
              @dismiss="dismissThemeHint"
            >
              {{ t('onboarding.themeHint') }}
            </OnboardingHintBubble>
          </transition>
          <transition name="notify-hint-fade">
            <OnboardingHintBubble
              v-if="showPushHint"
              placement="header-item"
              arrow="right"
              :close-label="t('common.cancel')"
              @dismiss="dismissPushHint"
            >
              {{ t('header.pushHint') }}
            </OnboardingHintBubble>
          </transition>
        </div>
      </div>

      <el-dropdown trigger="click" placement="bottom-end" @command="handleUserMenu">
        <button type="button" class="header-avatar" :title="userStore.displayName">
          <img v-if="userStore.avatarUrl" :src="resolveMediaUrl(userStore.avatarUrl)" alt="" />
          <el-icon v-else :size="18"><UserFilled /></el-icon>
        </button>
        <template #dropdown>
          <div class="user-menu-head">
            <div class="user-menu-name">{{ userStore.displayName }}</div>
            <div class="user-menu-sub">@{{ userStore.username }}</div>
          </div>
          <el-dropdown-menu>
            <el-dropdown-item command="profile" :icon="User">{{ t('header.profile') }}</el-dropdown-item>
            <el-dropdown-item command="password" :icon="Lock">{{ t('header.changePassword') }}</el-dropdown-item>
            <el-dropdown-item command="settings" :icon="Setting">{{ t('header.apiSettings') }}</el-dropdown-item>
            <el-dropdown-item divided command="logout" :icon="SwitchButton">{{ t('header.logout') }}</el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
    </div>
  </header>
</template>

<script setup>
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Bell, Lock, Setting, SwitchButton, User, UserFilled } from '@element-plus/icons-vue'
import { useUserStore } from '@/stores/user'
import { useNotificationsStore } from '@/stores/notifications'
import ThemeColorPicker from '@/components/ThemeColorPicker.vue'
import LanguagePicker from '@/components/LanguagePicker.vue'
import OnboardingHintBubble from '@/components/OnboardingHintBubble.vue'
import { useOnboardingHint } from '@/composables/useOnboardingHint'
import { useI18n } from 'vue-i18n'
import { APP_LOGO } from '@/constants/brand.js'
import { resolveMediaUrl } from '@/utils/media'

const router = useRouter()
const userStore = useUserStore()
const notificationsStore = useNotificationsStore()
const { t } = useI18n()
const { visible: showThemeHint, dismiss: dismissThemeHint } = useOnboardingHint('theme-color')
const { visible: showPushHint, dismiss: dismissPushHint } = useOnboardingHint('push')

function goConversation(conversationId) {
  if (!conversationId) return
  notificationsStore.markConversationRead(conversationId)
  router.push(`/app/chat/${conversationId}`)
}

function pushMessageKey(result) {
  if (result?.ok) {
    return result.mode === 'desktop' ? 'header.pushEnabledDesktop' : 'header.pushEnabledSuccess'
  }
  const map = {
    permission_denied: 'header.pushPermissionDenied',
    unsupported: 'header.pushUnsupported',
    no_public_key: 'header.pushNoPublicKey',
    subscribe_failed: 'header.pushSubscribeFailed',
  }
  return map[result?.reason] || 'header.pushSubscribeFailed'
}

async function togglePush() {
  if (notificationsStore.pushEnabled) {
    await notificationsStore.disablePush()
    ElMessage.success(t('header.pushDisabledSuccess'))
    return
  }
  const result = await notificationsStore.enablePush()
  if (result?.ok) {
    dismissPushHint()
    ElMessage.success(t(pushMessageKey(result)))
    return
  }
  ElMessage.warning(t(pushMessageKey(result)))
}

async function handleUserMenu(command) {
  if (command === 'profile') {
    router.push('/app/profile')
    return
  }
  if (command === 'password') {
    router.push({ path: '/app/profile', hash: '#password' })
    return
  }
  if (command === 'settings') {
    router.push('/app/settings')
    return
  }
  if (command === 'logout') {
    try {
      await ElMessageBox.confirm(t('header.logoutConfirm'), t('header.logout'), {
        confirmButtonText: t('header.logout'),
        cancelButtonText: t('common.cancel'),
        type: 'warning',
      })
    } catch {
      return
    }
    await userStore.logout()
    router.replace('/')
  }
}
</script>

<style lang="scss" scoped>
.app-header {
  position: sticky;
  top: 0;
  z-index: $z-header;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: $space-3 $space-5;
  margin: $space-3 $space-4 0;
  border-radius: $radius-pill;
  background: rgba(var(--ly-bg-surface-rgb), 0.55);
  backdrop-filter: blur(20px) saturate(130%);
  border: 1px solid rgba($color-pink-rgb, 0.1);
  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.18);
  overflow: visible;
  -webkit-app-region: drag;
}

.header-brand,
.header-actions,
.header-btn,
.header-avatar,
:deep(.lang-trigger),
:deep(.el-dropdown),
:deep(.el-popover__reference),
:deep(.el-badge),
:deep(button) {
  -webkit-app-region: no-drag;
}

.header-brand {
  display: flex;
  align-items: center;
  gap: $space-2;
  text-decoration: none;
}

.header-logo {
  width: 32px;
  height: 32px;
  border-radius: $radius-md;
  object-fit: cover;
  box-shadow: 0 4px 16px rgba($color-pink-rgb, 0.35);
}

.header-wordmark {
  font-size: $font-size-lg;
  font-weight: $font-weight-semibold;
  letter-spacing: 0.12em;
  color: $color-text-primary;
}

.header-actions {
  position: relative;
  display: flex;
  align-items: center;
  gap: $space-2;
  overflow: visible;
}

.header-guide-cluster {
  position: relative;
  display: flex;
  align-items: center;
  gap: $space-2;
  overflow: visible;
}

.theme-entry,
.notify-entry {
  position: relative;
  overflow: visible;
}

.header-hints-stack {
  position: absolute;
  right: 0;
  top: calc(100% + #{$space-2});
  display: flex;
  flex-direction: column;
  align-items: stretch;
  gap: $space-2;
  width: max-content;
  max-width: min(240px, calc(100vw - #{$space-8}));
  z-index: $z-header + 5;
}

.theme-hint-fade-enter-active,
.theme-hint-fade-leave-active,
.notify-hint-fade-enter-active,
.notify-hint-fade-leave-active {
  transition: opacity 0.2s ease, transform 0.2s ease;
}

.theme-hint-fade-enter-from,
.theme-hint-fade-leave-to,
.notify-hint-fade-enter-from,
.notify-hint-fade-leave-to {
  opacity: 0;
  transform: translateY(-4px);
}

.push-hint-fade-enter-active,
.push-hint-fade-leave-active {
  transition: opacity 0.2s ease, transform 0.2s ease;
}

.push-hint-fade-enter-from,
.push-hint-fade-leave-to {
  opacity: 0;
  transform: translateY(-4px);
}

.header-btn {
  width: 38px;
  height: 38px;
  border-radius: $radius-full;
  display: flex;
  align-items: center;
  justify-content: center;
  color: $color-text-secondary;
  transition: background $transition-fast, color $transition-fast;

  &:hover {
    color: $color-pink-primary;
    background: rgba($color-pink-rgb, 0.1);
  }
}

.header-avatar {
  width: 38px;
  height: 38px;
  border-radius: $radius-full;
  overflow: hidden;
  border: 2px solid rgba($color-pink-rgb, 0.35);
  display: flex;
  align-items: center;
  justify-content: center;
  color: $color-text-inverse;
  background: linear-gradient(135deg, $color-pink-primary, $color-pink-dark);
  cursor: pointer;

  img {
    width: 100%;
    height: 100%;
    object-fit: cover;
  }
}

.user-menu-head {
  padding: $space-3 $space-4 $space-2;
  border-bottom: 1px solid rgba($color-pink-rgb, 0.08);
  margin-bottom: $space-1;
}

.user-menu-name {
  font-size: $font-size-sm;
  font-weight: $font-weight-semibold;
  color: $color-text-primary;
}

.user-menu-sub {
  margin-top: 2px;
  font-size: $font-size-xs;
  color: $color-text-muted;
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
</style>
