<template>
  <div class="launcher-pick">
    <header class="launcher-pick__head">
      <h2 class="launcher-pick__title">选择角色</h2>
      <button type="button" class="launcher-pick__close" @click="closePicker">×</button>
    </header>

    <div v-if="!userStore.isLoggedIn" class="launcher-pick__empty">
      <p>请先登录后再快速开聊</p>
      <el-button type="primary" size="small" @click="openLogin">去登录</el-button>
    </div>

    <div v-else-if="loading" class="launcher-pick__loading">
      <el-icon class="is-loading" :size="20"><Loading /></el-icon>
    </div>

    <div v-else-if="!characters.length" class="launcher-pick__empty">
      <p>还没有角色，去广场添加吧</p>
      <el-button type="primary" size="small" @click="openMain">打开主窗口</el-button>
    </div>

    <ul v-else class="launcher-pick__list">
      <li v-for="char in characters" :key="char.id">
        <button type="button" class="launcher-pick__item" :disabled="openingId === char.id" @click="startChat(char)">
          <span class="launcher-pick__avatar">
            <img v-if="char.avatarUrl" :src="resolveMediaUrl(char.avatarUrl)" :alt="char.name" />
            <el-icon v-else :size="16"><User /></el-icon>
          </span>
          <span class="launcher-pick__name">{{ char.name }}</span>
          <span
            v-if="unreadCountForCharacter(char.id) > 0"
            class="launcher-pick__badge"
          >{{ formatBadgeCount(unreadCountForCharacter(char.id)) }}</span>
          <el-icon v-if="openingId === char.id" class="is-loading launcher-pick__loading-icon" :size="14"><Loading /></el-icon>
        </button>
      </li>
    </ul>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { User, Loading } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { useUserStore } from '@/stores/user'
import { useCharactersStore } from '@/stores/characters'
import { useConversationsStore } from '@/stores/conversations'
import { useNotificationsStore } from '@/stores/notifications'
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
const notificationsStore = useNotificationsStore()
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
let unsubscribeLauncherMessage = null

function showNewMessageHint(payload = {}) {
  const name = payload.characterName || t('launcher.defaultCharacterName')
  ElMessage.info(t('launcher.newMessageHint', { name }))
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
  if (!char?.id) return
  openingId.value = char.id
  try {
    const convs = await conversationsStore.fetchList({ force: true })
    const existing = (convs || []).find(c => c.mode === 'SINGLE' && c.characterId === char.id)
    const convId = existing?.id
      ?? (await createConversation({ characterId: char.id, mode: 'SINGLE' })).id
    await getElectronAPI()?.openQuickChat?.(convId)
  } catch (err) {
    ElMessage.error(humanizeError(err, '无法开始聊天，请稍后再试'))
  } finally {
    openingId.value = null
  }
}

onMounted(async () => {
  unsubscribeLauncherMessage = getElectronAPI()?.onLauncherNewMessage?.(showNewMessageHint)
  if (!userStore.token) {
    loading.value = false
    return
  }
  try {
    if (!userStore.userId) {
      await userStore.fetchProfile()
    }
    void notificationsStore.init()
    const [charList, convList, unreadList] = await Promise.all([
      charactersStore.fetchList({ force: true }),
      conversationsStore.fetchList({ force: true, silent: true }).catch(() => []),
      listNotifications({ unreadOnly: true, limit: 200 }, { silent: true }).catch(() => [])
    ])
    characters.value = charList || []
    ingestConversations(convList || [])
    ingestUnreadNotifications(unreadList || [])
  } catch {
    characters.value = []
  } finally {
    loading.value = false
  }
})

onUnmounted(() => {
  unsubscribeLauncherMessage?.()
})
</script>

<style lang="scss">
html:has(.launcher-pick),
body:has(.launcher-pick),
#app:has(.launcher-pick) {
  background: transparent !important;
}
</style>

<style lang="scss" scoped>
.launcher-pick {
  width: 320px;
  height: 420px;
  display: flex;
  flex-direction: column;
  border-radius: 16px;
  background: rgba(14, 14, 22, 0.94);
  border: 1px solid rgba(255, 255, 255, 0.08);
  box-shadow: 0 16px 48px rgba(0, 0, 0, 0.45);
  backdrop-filter: blur(16px);
  overflow: hidden;
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
  padding: 0 4px;
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
</style>
