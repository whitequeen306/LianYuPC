<template>
  <div class="launcher-pick">
    <header class="launcher-pick__head">
      <h2 class="launcher-pick__title">选择角色</h2>
      <button type="button" class="launcher-pick__close" @click="closePicker">×</button>
    </header>

    <div v-if="!userStore.isLoggedIn" class="launcher-pick__empty">
      <p>请先登录后再快速开聊</p>
      <button type="button" class="launcher-pick__btn" @click="openLogin">去登录</button>
    </div>

    <div v-else-if="loading" class="launcher-pick__loading">
      <span class="launcher-pick__spinner" aria-hidden="true" />
    </div>

    <div v-else-if="!characters.length" class="launcher-pick__empty">
      <p>还没有角色，去广场添加吧</p>
      <button type="button" class="launcher-pick__btn" @click="openMain">打开主窗口</button>
    </div>

    <ul v-else class="launcher-pick__list">
      <li v-for="char in characters" :key="char.id">
        <button
          type="button"
          class="launcher-pick__item"
          :disabled="openingId === char.id"
          @click="startChat(char)"
        >
          <span class="launcher-pick__avatar">
            <CharacterAvatarImg :character="char" :alt="char.name" :icon-size="18" />
          </span>
          <span class="launcher-pick__name">{{ char.name }}</span>
          <span
            v-if="unreadCountForCharacter(char.id) > 0"
            class="launcher-pick__badge"
          >{{ formatBadgeCount(unreadCountForCharacter(char.id)) }}</span>
          <span v-if="openingId === char.id" class="launcher-pick__spinner launcher-pick__spinner--inline" aria-hidden="true" />
        </button>
      </li>
    </ul>
    <p v-if="toastText" class="launcher-pick__toast" role="status">{{ toastText }}</p>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { refreshLauncherSession } from '@/auth/launcherBootstrap'
import { getActivePinia } from 'pinia'
import { useUserStore } from '@/stores/user'
import { useCharactersStore } from '@/stores/characters'
import { useConversationsStore } from '@/stores/conversations'
import { createConversation } from '@/api/conversation'
import { listNotifications } from '@/api/notification'
import { useConversationUnread } from '@/composables/useConversationUnread'
import CharacterAvatarImg from '@/components/CharacterAvatarImg.vue'
import { humanizeError } from '@/utils/errorMessage'
import { getElectronAPI } from '@/utils/electron'

const { t } = useI18n()
const userStore = useUserStore()
const charactersStore = useCharactersStore()
const conversationsStore = useConversationsStore()
const {
  ingestConversations,
  ingestUnreadNotifications,
  refreshUnreadFromApi,
  unreadCountForCharacter,
  formatBadgeCount
} = useConversationUnread()

const loading = ref(true)
const characters = ref([])
const openingId = ref(null)
const toastText = ref('')
let toastTimer = null
let unsubscribeLauncherMessage = null

function showToast(text, ms = 2600) {
  toastText.value = text
  clearTimeout(toastTimer)
  toastTimer = setTimeout(() => { toastText.value = '' }, ms)
}

async function refreshPickerData() {
  openingId.value = null
  const userStore = useUserStore()
  if (!userStore.token) {
    await refreshLauncherSession(getActivePinia())
  }

  if (!userStore.isLoggedIn) {
    loading.value = false
    characters.value = []
    return
  }

  if (charactersStore.list.length > 0) {
    characters.value = charactersStore.list
    loading.value = false
  } else {
    loading.value = true
  }

  try {
    if (!userStore.userId) {
      void userStore.fetchProfile({ skipGlobalError: true })
    }
    const [charList, convList, unreadList] = await Promise.all([
      charactersStore.fetchList(),
      conversationsStore.fetchList({ silent: true }).catch(() => []),
      listNotifications({ unreadOnly: true, limit: 200 }, { silent: true }).catch(() => []),
    ])
    characters.value = charList || []
    ingestConversations(convList || [])
    ingestUnreadNotifications(unreadList || [])
  } catch {
    if (!characters.value.length) characters.value = []
  } finally {
    loading.value = false
  }
}

function showNewMessageHint(payload = {}) {
  const name = payload.characterName || t('launcher.defaultCharacterName')
  showToast(t('launcher.newMessageHint', { name }))
  void refreshUnreadFromApi()
}

function closePicker() {
  getElectronAPI()?.closePicker?.()
}

function openMain(hash = '#/app') {
  getElectronAPI()?.openMainWindow?.(hash)
  closePicker()
}

function openLogin() {
  openMain('#/login')
}

async function startChat(char) {
  if (!char?.id || openingId.value) return
  openingId.value = char.id
  try {
    let convId = (conversationsStore.list || []).find(
      (c) => c.mode === 'SINGLE' && String(c.characterId) === String(char.id),
    )?.id

    if (!convId) {
      const convs = await conversationsStore.fetchList({ force: true, silent: true })
      convId = (convs || []).find(
        (c) => c.mode === 'SINGLE' && String(c.characterId) === String(char.id),
      )?.id
    }

    if (!convId) {
      const created = await createConversation({ characterId: char.id, mode: 'SINGLE' })
      convId = created?.id
      if (created?.id) conversationsStore.prepend(created)
    }

    if (!convId) {
      throw new Error('无法创建会话')
    }

    closePicker()
    getElectronAPI()?.openQuickChat?.(convId)
  } catch (err) {
    showToast(humanizeError(err, '无法开始聊天，请稍后再试'))
  } finally {
    openingId.value = null
  }
}

onMounted(async () => {
  unsubscribeLauncherMessage = getElectronAPI()?.onLauncherNewMessage?.(showNewMessageHint)
  await refreshPickerData()
})

onUnmounted(() => {
  clearTimeout(toastTimer)
  unsubscribeLauncherMessage?.()
})
</script>

<style lang="scss" scoped>
.launcher-pick {
  width: 100%;
  height: 320px;
  min-height: 320px;
  max-height: 320px;
  flex: 0 0 320px;
  display: flex;
  flex-direction: column;
  position: relative;
  // Opaque fill — no backdrop-filter: Electron transparent windows paint black
  // edges with blur until the window is moved (Windows DWM).
  border-radius: $radius-lg;
  background: var(--ly-bg-glass-strong, rgba(30, 39, 50, 0.96));
  border: 1px solid rgba($color-pink-rgb, 0.18);
  overflow: hidden;
  pointer-events: auto;
  transform: translateZ(0);
  contain: paint;
}

.launcher-pick__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 14px;
  border-bottom: 1px solid rgba($color-pink-rgb, 0.12);
}

.launcher-pick__title {
  margin: 0;
  font-size: 15px;
  font-weight: 600;
  color: var(--ly-text-primary);
}

.launcher-pick__close {
  border: none;
  background: transparent;
  color: var(--ly-text-muted);
  font-size: 22px;
  line-height: 1;
  cursor: pointer;
  padding: 4px 8px;
  pointer-events: auto;

  &:hover {
    color: var(--ly-text-primary);
  }
}

.launcher-pick__loading,
.launcher-pick__empty {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
  padding: 24px;
  text-align: center;
  color: var(--ly-text-secondary);
  font-size: 13px;
}

.launcher-pick__list {
  list-style: none;
  margin: 0;
  padding: 8px;
  overflow-y: auto;
  flex: 1;
  min-height: 0;
}

.launcher-pick__item {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 12px;
  border: none;
  border-radius: $radius-md;
  background: transparent;
  color: var(--ly-text-primary);
  cursor: pointer;
  text-align: left;
  pointer-events: auto;
  transition: background 0.22s cubic-bezier(0.23, 1, 0.32, 1);

  &:hover:not(:disabled) {
    background: rgba($color-pink-rgb, 0.12);
  }

  &:disabled {
    opacity: 0.7;
    cursor: wait;
  }
}

.launcher-pick__avatar {
  position: relative;
  width: 36px;
  height: 36px;
  border-radius: 50%;
  overflow: hidden;
  background: rgba(255, 255, 255, 0.06);
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;

  img {
    width: 100%;
    height: 100%;
    object-fit: cover;
  }
}

.launcher-pick__badge {
  flex-shrink: 0;
  min-width: 18px;
  height: 18px;
  padding: 0 5px;
  border-radius: 9999px;
  background: $color-error;
  color: #fff;
  font-size: 10px;
  font-weight: 700;
  line-height: 18px;
  text-align: center;
}

.launcher-pick__name {
  flex: 1;
  min-width: 0;
  font-size: 14px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.launcher-pick__loading-icon {
  flex-shrink: 0;
}

.launcher-pick__btn {
  border: none;
  border-radius: $radius-pill;
  padding: 8px 14px;
  background: $color-pink-primary;
  color: var(--ly-text-inverse);
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  pointer-events: auto;
}

.launcher-pick__spinner {
  width: 20px;
  height: 20px;
  border: 2px solid rgba($color-pink-rgb, 0.2);
  border-top-color: $color-pink-primary;
  border-radius: 50%;
  animation: launcher-pick-spin 0.8s linear infinite;
}

.launcher-pick__spinner--inline {
  width: 14px;
  height: 14px;
  flex-shrink: 0;
}

.launcher-pick__avatar-fallback {
  font-size: 12px;
  color: var(--ly-text-muted);
}

.launcher-pick__toast {
  position: absolute;
  left: 50%;
  bottom: 8px;
  transform: translateX(-50%);
  margin: 0;
  padding: 6px 10px;
  border-radius: $radius-md;
  background: var(--ly-bg-glass-strong, rgba(30, 39, 50, 0.92));
  color: var(--ly-text-primary);
  font-size: 12px;
  pointer-events: none;
}

@keyframes launcher-pick-spin {
  to { transform: rotate(360deg); }
}
</style>
