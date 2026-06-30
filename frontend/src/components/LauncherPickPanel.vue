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
            <img v-if="char.avatarUrl" :src="resolveMediaUrl(char.avatarUrl)" :alt="char.name" />
            <span v-else class="launcher-pick__avatar-fallback" aria-hidden="true">?</span>
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
import { readToken, syncSetTokenCache } from '@/utils/secureToken'
import { useUserStore } from '@/stores/user'
import { useCharactersStore } from '@/stores/characters'
import { useConversationsStore } from '@/stores/conversations'
import { createConversation } from '@/api/conversation'
import { listNotifications } from '@/api/notification'
import { useConversationUnread } from '@/composables/useConversationUnread'
import { resolveMediaUrl } from '@/utils/media'
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

  if (!userStore.token) {
    const token = await readToken()
    if (token) {
      userStore.token = token
      syncSetTokenCache(token)
    }
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
  border-radius: 16px 16px 0 0;
  background: rgba(14, 14, 22, 0.96);
  border: 1px solid rgba(255, 255, 255, 0.08);
  border-bottom: none;
  box-shadow: 0 -8px 32px rgba(0, 0, 0, 0.35);
  backdrop-filter: blur(16px);
  overflow: hidden;
  pointer-events: auto;
}

.launcher-pick__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 14px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.06);
}

.launcher-pick__title {
  margin: 0;
  font-size: 15px;
  font-weight: 600;
  color: #f5f5f7;
}

.launcher-pick__close {
  border: none;
  background: transparent;
  color: #a1a1aa;
  font-size: 22px;
  line-height: 1;
  cursor: pointer;
  padding: 4px 8px;
  pointer-events: auto;

  &:hover {
    color: #f5f5f7;
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
  color: #a1a1aa;
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
  border-radius: 12px;
  background: transparent;
  color: #f5f5f7;
  cursor: pointer;
  text-align: left;
  pointer-events: auto;

  &:hover:not(:disabled) {
    background: rgba(236, 72, 153, 0.12);
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
  border-radius: 999px;
  background: #ff4d4f;
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
  border-radius: 10px;
  padding: 8px 14px;
  background: #f4a6b5;
  color: #fff;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  pointer-events: auto;
}

.launcher-pick__spinner {
  width: 20px;
  height: 20px;
  border: 2px solid rgba(244, 166, 181, 0.2);
  border-top-color: #f4a6b5;
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
  color: #a1a1aa;
}

.launcher-pick__toast {
  position: absolute;
  left: 50%;
  bottom: 8px;
  transform: translateX(-50%);
  margin: 0;
  padding: 6px 10px;
  border-radius: 8px;
  background: rgba(20, 20, 28, 0.92);
  color: #f5f5f7;
  font-size: 12px;
  pointer-events: none;
}

@keyframes launcher-pick-spin {
  to { transform: rotate(360deg); }
}
</style>
