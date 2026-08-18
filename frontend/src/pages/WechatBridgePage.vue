<template>
  <div class="wechat-bridge-page stagger-container">
    <button class="page-back" type="button" @click="goBack">
      <el-icon><ArrowLeft /></el-icon>
      <span>{{ t('common.back') }}</span>
    </button>
    <header class="page-header">
      <h1 class="page-title">{{ t('routes.wechatBridge') }}</h1>
      <p class="page-desc">{{ t('wechatBridge.desc') }}</p>
    </header>

    <section v-if="!isElectron" class="section stagger-item">
      <div class="glass wechat-card">
        <p class="hint">{{ t('wechatBridge.notElectron') }}</p>
      </div>
    </section>

    <template v-else>
      <section class="section stagger-item">
        <div class="section-header">
          <div>
            <h2 class="section-title">{{ t('wechatBridge.status.title') }}</h2>
            <p class="section-desc">{{ t('wechatBridge.status.hint') }}</p>
          </div>
          <div class="host-actions">
            <el-button
              v-if="hostRunning"
              type="danger"
              plain
              :loading="actionInFlight"
              @click="onStop"
            >{{ t('wechatBridge.host.stop') }}</el-button>
            <el-button
              v-else
              type="primary"
              :loading="actionInFlight"
              @click="onStart"
            >{{ t('wechatBridge.host.start') }}</el-button>
            <el-button
              type="primary"
              :disabled="!hostRunning"
              @click="onLogin"
            >{{ t('wechatBridge.host.login') }}</el-button>
            <el-button
              type="warning"
              plain
              :loading="reinstalling"
              @click="onReinstall"
            >{{ t('wechatBridge.host.reinstall') }}</el-button>
          </div>
        </div>
        <div class="glass wechat-card">
          <p class="hint">
            {{ t('wechatBridge.host.stateLabel') }}:
            {{ t('wechatBridge.state.' + (hostStatus.state || 'stopped')) }}
            <span v-if="hostStatus.version"> · {{ t('wechatBridge.host.version') }} {{ hostStatus.version }}</span>
            <span v-if="hostStatus.loggedIn"> · {{ t('wechatBridge.host.loggedIn') }}</span>
          </p>
          <p v-if="hostStatus.lastError" class="hint error-hint">{{ hostStatus.lastError }}</p>
          <div v-if="downloadProgress" class="download-row">
            <el-progress :percentage="downloadProgress.percent || 0" />
            <span class="hint">{{ t('wechatBridge.download.phase.' + (downloadProgress.phase || 'downloading')) }}</span>
          </div>
          <div v-if="qrSrc" class="qr-wrap">
            <img :src="qrSrc" alt="ClawBot QR" class="qr-img" />
            <p class="hint">{{ t('wechatBridge.host.scanHint') }}</p>
          </div>
        </div>
      </section>

      <section class="section stagger-item">
        <div class="section-header">
          <div><h2 class="section-title">{{ t('wechatBridge.binding.title') }}</h2></div>
        </div>
        <div class="glass wechat-card">
          <el-form label-position="top">
            <el-form-item :label="t('wechatBridge.binding.character')">
              <el-select
                v-model="bindingForm.characterId"
                :placeholder="t('wechatBridge.binding.characterPlaceholder')"
                :loading="characterLoading"
                filterable
                style="width: 100%"
              >
                <el-option
                  v-for="opt in characterOptions"
                  :key="opt.value"
                  :label="opt.label"
                  :value="opt.value"
                />
              </el-select>
              <p class="field-hint">{{ t('wechatBridge.binding.characterHint') }}</p>
            </el-form-item>
            <el-form-item :label="t('wechatBridge.binding.provider')">
              <el-select
                v-model="bindingForm.provider"
                style="width: 100%"
                :placeholder="t('wechatBridge.binding.providerPlaceholder')"
              >
                <el-option
                  v-for="v in providersStore.textVaults"
                  :key="v.provider"
                  :label="v.provider"
                  :value="v.provider"
                />
              </el-select>
              <p class="field-hint">{{ t('wechatBridge.binding.providerHint') }}</p>
            </el-form-item>
            <el-form-item :label="t('wechatBridge.binding.model')">
              <el-input
                v-model="bindingForm.model"
                :placeholder="t('wechatBridge.binding.modelPlaceholder')"
              />
              <p class="field-hint">{{ t('wechatBridge.binding.modelHint') }}</p>
            </el-form-item>
            <el-button type="primary" @click="onSaveBinding">{{ t('wechatBridge.binding.save') }}</el-button>
          </el-form>
        </div>
      </section>
    </template>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import { ArrowLeft } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { isElectronApp } from '@/utils/electron'
import { listCharacters } from '@/api/character'
import { useProvidersStore } from '@/stores/providers'
import { useWechatBridgeStore } from '@/stores/wechatBridge'

const { t } = useI18n()
const router = useRouter()
const isElectron = isElectronApp()
const store = useWechatBridgeStore()
const providersStore = useProvidersStore()
const goBack = () => router.push('/app/settings')

const actionInFlight = ref(false)
const reinstalling = ref(false)
const characterLoading = ref(false)
const characterOptions = ref([])
const bindingForm = reactive({
  characterId: '',
  provider: '',
  model: '',
})

const hostStatus = computed(() => store.hostStatus)
const downloadProgress = computed(() => store.downloadProgress)
const hostRunning = computed(() => Boolean(hostStatus.value?.running || hostStatus.value?.state === 'running'))
const qrSrc = computed(() => hostStatus.value?.qrDataUrl || '')

function reasonText(reason) {
  const key = 'wechatBridge.reason.' + (reason || 'unknown')
  return t(key)
}

async function loadCharacters() {
  characterLoading.value = true
  try {
    const chars = await listCharacters({ silent: true })
    characterOptions.value = (Array.isArray(chars) ? chars : []).map((c) => ({
      value: String(c.id),
      label: c.name || `#${c.id}`,
    }))
  } catch {
    characterOptions.value = []
  } finally {
    characterLoading.value = false
  }
}

function applySettings() {
  const s = store.settings
  bindingForm.characterId = s?.binding?.characterId || ''
  bindingForm.provider = s?.binding?.provider || ''
  bindingForm.model = s?.binding?.model || ''
}

async function onSaveBinding() {
  if (!bindingForm.characterId) {
    ElMessage.warning(t('wechatBridge.binding.noCharacter'))
    return
  }
  if (!bindingForm.provider) {
    ElMessage.warning(t('wechatBridge.binding.providerRequired'))
    return
  }
  await store.setSettings({
    binding: {
      characterId: bindingForm.characterId,
      provider: bindingForm.provider,
      model: bindingForm.model,
    },
  })
  ElMessage.success(t('wechatBridge.binding.saved'))
}

async function onStart() {
  if (!bindingForm.characterId || !bindingForm.provider) {
    ElMessage.warning(t('wechatBridge.binding.providerRequired'))
    return
  }
  await onSaveBinding()
  if (!store.settings?.hosting?.consented) {
    try {
      await ElMessageBox.confirm(t('wechatBridge.consent.body'), t('wechatBridge.consent.title'), {
        confirmButtonText: t('wechatBridge.consent.confirm'),
        cancelButtonText: t('wechatBridge.consent.cancel'),
        type: 'warning',
      })
    } catch {
      return
    }
    await store.setSettings({ hosting: { consented: true } })
  }
  actionInFlight.value = true
  try {
    const r = await store.startHost()
    if (r && r.ok === false) ElMessage.warning(reasonText(r.reason))
  } finally {
    actionInFlight.value = false
  }
}

async function onStop() {
  actionInFlight.value = true
  try {
    const r = await store.stopHost()
    if (r && r.ok === false) ElMessage.warning(t('wechatBridge.host.stopFailed'))
  } finally {
    actionInFlight.value = false
  }
}

async function onLogin() {
  const r = await store.requestLogin()
  if (r && r.ok === false) ElMessage.warning(t('wechatBridge.host.loginFailed'))
}

async function onReinstall() {
  try {
    await ElMessageBox.confirm(t('wechatBridge.host.reinstallConfirm'), t('wechatBridge.host.reinstall'), {
      confirmButtonText: t('wechatBridge.consent.confirm'),
      cancelButtonText: t('wechatBridge.consent.cancel'),
      type: 'warning',
    })
  } catch {
    return
  }
  reinstalling.value = true
  try {
    const r = await store.reinstallHost()
    if (r && r.ok === false) ElMessage.warning(reasonText(r.reason))
    await store.refreshStatus()
  } finally {
    reinstalling.value = false
  }
}

onMounted(() => {
  if (!isElectron) return
  providersStore.fetchVaults()
  store.syncFromMain().then(applySettings)
  loadCharacters()
})

onUnmounted(() => {
  store.dispose()
})
</script>

<style lang="scss" scoped>
.wechat-bridge-page {
  max-width: $narrow-page-max;
}

.page-header {
  margin-bottom: $space-10;
}

.page-title {
  font-size: $font-size-2xl;
  font-weight: $font-weight-bold;
  color: $color-text-primary;
  margin-bottom: $space-2;
}

.page-desc {
  font-size: $font-size-sm;
  color: $color-text-muted;
}

.section + .section {
  margin-top: $space-12;
}

.section-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: $space-4;
  margin-bottom: $space-6;
}

.section-title {
  font-size: $font-size-lg;
  font-weight: $font-weight-semibold;
  color: $color-text-primary;
  margin-bottom: $space-1;
}

.section-desc {
  font-size: $font-size-sm;
  color: $color-text-muted;
}

.wechat-card {
  border-radius: $radius-lg;
  padding: $space-5 $space-6;
  display: flex;
  flex-direction: column;
  gap: $space-4;
}

.hint {
  color: $color-text-muted;
  font-size: $font-size-sm;
}

.error-hint {
  color: $color-error;
}

.field-hint {
  margin-top: $space-1;
  font-size: $font-size-xs;
  color: $color-text-muted;
}

.host-actions {
  display: flex;
  flex-wrap: wrap;
  gap: $space-2;
}

.qr-wrap {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: $space-3;
}

.qr-img {
  width: 220px;
  height: 220px;
  border-radius: $radius-md;
}

.download-row {
  display: flex;
  flex-direction: column;
  gap: $space-2;
}
</style>
