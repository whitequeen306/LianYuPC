<template>
  <div class="settings-page stagger-container">
    <button class="page-back" type="button" @click="goBack">
      <el-icon><ArrowLeft /></el-icon>
      <span>返回</span>
    </button>
    <header class="page-header">
      <h1 class="page-title">{{ t('settings.title') }}</h1>
      <p class="page-desc">{{ t('settings.desc') }}</p>
    </header>

    <!-- Desktop quick entry -->
    <section v-if="isElectron" class="section stagger-item desktop-section">
      <div class="section-header">
        <div>
          <h2 class="section-title">桌面快捷入口</h2>
          <p class="section-desc">关闭主窗口后保留托盘与桌面 Logo，随时快速开聊</p>
        </div>
      </div>
      <div class="desktop-settings glass">
        <div class="desktop-settings__row">
          <div>
            <div class="desktop-settings__label">关闭主窗口时保留快捷入口</div>
            <div class="desktop-settings__hint">关闭后最小化到托盘，不会彻底退出</div>
          </div>
          <el-switch v-model="desktopForm.closeToTray" @change="onDesktopChange" />
        </div>
        <div class="desktop-settings__row">
          <div>
            <div class="desktop-settings__label">启用桌面桌宠</div>
            <div class="desktop-settings__hint">关闭主窗口后在桌面显示角色像素桌宠，点击可快速开聊</div>
          </div>
          <el-switch v-model="desktopForm.showDesktopPet" @change="onDesktopChange" />
        </div>
        <div v-if="desktopForm.showDesktopPet" class="desktop-settings__row">
          <div>
            <div class="desktop-settings__label">允许桌宠观察屏幕</div>
            <div class="desktop-settings__hint">
              开启后桌宠会定期截取屏幕与窗口标题，上传至服务器进行 AI 分析并主动问候。截图不会长期保存。
            </div>
          </div>
          <el-switch
            v-model="desktopForm.allowScreenObserve"
            @change="onScreenObserveChange"
          />
        </div>
        <div class="desktop-settings__row">
          <div>
            <div class="desktop-settings__label">开机自动启动</div>
            <div class="desktop-settings__hint">登录 Windows 后自动在后台启动 LianYu</div>
          </div>
          <el-switch v-model="desktopForm.launchAtLogin" @change="onDesktopChange" />
        </div>
        <div class="desktop-settings__row">
          <div>
            <div class="desktop-settings__label">QQ 桥接（beta）</div>
            <div class="desktop-settings__hint">让 QQ 消息转发给云端 AI 并自动回复</div>
          </div>
          <el-button text :icon="Promotion" @click="goQqBridge">前往配置</el-button>
        </div>
        <div v-if="desktopForm.showDesktopPet" class="desktop-settings__pet-block">
          <div class="desktop-settings__label">桌宠角色</div>
          <div class="desktop-settings__hint">选择关闭主窗口后在桌面显示的角色形象</div>
          <div class="pet-picker">
            <button
              v-for="pet in petCatalog"
              :key="pet.id"
              type="button"
              class="pet-picker__item"
              :class="{ 'is-active': desktopForm.launcherPetId === pet.id }"
              @click="selectPet(pet.id)"
            >
              <img :src="pet.previewUrl" :alt="petDisplayName(pet)" class="pet-picker__img" />
              <span class="pet-picker__name">{{ petDisplayName(pet) }}</span>
            </button>
          </div>
        </div>
      </div>
    </section>

    <!-- Provider Section -->
    <section class="section stagger-item">
      <div class="section-header">
        <div>
          <h2 class="section-title">{{ t('settings.aiSection') }}</h2>
          <p class="section-desc">{{ t('settings.aiDesc') }}</p>
        </div>
        <el-button type="primary" class="btn-cta" @click="showAddDialog" :icon="Plus">
          {{ t('settings.addConfig') }}
        </el-button>
      </div>

      <div v-if="providersStore.loading" class="loading-state">
        <el-icon class="is-loading" :size="24"><Loading /></el-icon>
        <span>{{ t('common.loading') }}</span>
      </div>

      <div v-else-if="providersStore.vaults.length === 0" class="empty-state">
        <div class="empty-icon">
          <el-icon :size="40"><Connection /></el-icon>
        </div>
        <h3>{{ t('settings.emptyTitle') }}</h3>
        <p>{{ t('settings.emptyDesc') }}</p>
        <el-button type="primary" class="btn-cta btn-cta-lg" :icon="Plus" @click="showAddDialog">
          {{ t('settings.addFirst') }}
        </el-button>
      </div>

      <div v-else class="provider-grid">
        <div
          v-for="vault in providersStore.vaults"
          :key="vault.id"
          class="provider-card glass stagger-item"
        >
          <div class="card-top">
            <div class="provider-badge" :class="vault.provider.toLowerCase()">
              {{ vault.provider }}
            </div>
            <div class="card-actions">
              <el-button text :icon="Edit" size="small" @click="showEditDialog(vault)" />
              <el-button text :icon="Delete" size="small" class="btn-delete" @click="confirmDelete(vault)" />
            </div>
          </div>

          <div class="card-body">
            <div class="info-row">
              <span class="info-label">Base URL</span>
              <span class="info-value">{{ vault.baseUrl || '默认' }}</span>
            </div>
            <div class="info-row">
              <span class="info-label">默认模型</span>
              <span class="info-value">{{ vault.modelDefault || '未设置' }}</span>
            </div>
            <div class="info-row">
              <span class="info-label">密钥版本</span>
              <span class="info-value mono">{{ vault.keyVersion }}</span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- 诊断日志 -->
    <section v-if="isElectron" class="section stagger-item">
      <div class="section-header">
        <div>
          <h2 class="section-title">诊断日志</h2>
          <p class="section-desc">记录应用运行时的全部错误、警告与生命周期事件，便于排查问题</p>
        </div>
      </div>
      <div class="desktop-settings glass">
        <div class="desktop-settings__row">
          <div>
            <div class="desktop-settings__label">导出日志</div>
            <div class="desktop-settings__hint">将全部日志导出为文本文件（含主进程、渲染进程、NapCat 子进程）</div>
          </div>
          <el-button type="primary" :icon="Download" :loading="exportingLogs" @click="exportLogs">导出</el-button>
        </div>
        <div class="desktop-settings__row">
          <div>
            <div class="desktop-settings__label">打开日志文件夹</div>
            <div class="desktop-settings__hint">在资源管理器中打开日志文件所在目录</div>
          </div>
          <el-button text :icon="FolderOpened" @click="openLogFolder">打开文件夹</el-button>
        </div>
      </div>
    </section>

    <!-- 关于 -->
    <section class="section stagger-item">
      <div class="section-header">
        <div>
          <h2 class="section-title">{{ t('about.title') }}</h2>
          <p class="section-desc">{{ t('about.desc') }}</p>
        </div>
        <el-button type="primary" class="btn-cta" :icon="Promotion" @click="goAbout">{{ t('about.viewDetail') }}</el-button>
      </div>
    </section>

    <!-- Add/Edit Dialog -->
    <el-dialog
      v-model="dialogVisible"
      :title="editingVault ? '编辑 AI 配置' : '添加 AI 配置'"
      :width="dialogWidth"
      destroy-on-close
    >
      <el-form
        ref="formRef"
        :model="form"
        :rules="formRules"
        label-position="top"
        @submit.prevent="handleSubmit"
      >
        <el-form-item label="配置别名（可选）" prop="provider">
          <el-input
            v-model="form.provider"
            placeholder="留空将自动命名为 Provider+编号，例如 Provider3"
            maxlength="32"
            show-word-limit
            :disabled="!!editingVault"
          />
          <p class="field-hint">
            仅用于在本应用中辨认这套配置（对话页下拉里显示），与接口地址无关。
            不填则保存后自动命名为 <code>Provider</code> + 数据库编号（如 <code>Provider3</code>）。
          </p>
        </el-form-item>

        <el-form-item label="API Key（密钥）" prop="apiKey">
          <el-input
            v-model="form.apiKey"
            type="password"
            show-password
            :placeholder="apiKeyPlaceholder"
          />
          <p v-if="editingVault" class="field-hint">留空则保持原 API Key 不变</p>
        </el-form-item>

        <el-form-item label="接口地址 Base URL（必填）" prop="baseUrl">
          <el-input
            v-model="form.baseUrl"
            placeholder="例如 https://api.deepseek.com 或 http://localhost:11434"
          />
          <p class="field-hint">决定实际连接哪个 AI 服务，须以 http:// 或 https:// 开头。DeepSeek 官方填 <code>https://api.deepseek.com</code>（不要带 /v1）。</p>
        </el-form-item>

        <el-form-item label="默认模型（必填）" prop="modelDefault">
          <div class="model-input-row">
            <el-select
              v-model="form.modelDefault"
              filterable
              allow-create
              default-first-option
              :loading="dialogModelsLoading"
              placeholder="点击右侧按钮拉取，或手动输入模型名"
              class="model-select"
            >
              <el-option
                v-for="m in dialogModels"
                :key="m.id"
                :label="m.name || m.id"
                :value="m.id"
              />
            </el-select>
            <el-button
              :icon="RefreshRight"
              :loading="dialogModelsLoading"
              @click="handleFetchDialogModels"
            >
              拉取模型
            </el-button>
          </div>
          <p class="field-hint">填写接口地址和密钥后点击「拉取模型」自动获取可用列表，也可手动输入。</p>
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button type="default" @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" class="btn-cta" :loading="submitting" @click="handleSubmit">
          {{ editingVault ? '保存' : '添加' }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useResponsiveDialogWidth } from '@/composables/useResponsiveDialogWidth'
import { useI18n } from 'vue-i18n'
import { useProvidersStore } from '@/stores/providers'
import { useDesktopStore } from '@/stores/desktop'
import { useSettingsStore } from '@/stores/settings'
import { getElectronAPI, isElectronApp } from '@/utils/electron'
import { PET_CATALOG, getPetPreviewUrl } from '@/constants/petCatalog'

const { t } = useI18n()
import { Plus, Edit, Delete, RefreshRight, Loading, Connection, Promotion, ArrowLeft, Download, FolderOpened } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'

const providersStore = useProvidersStore()
const desktopStore = useDesktopStore()
const settingsStore = useSettingsStore()
const router = useRouter()
const goQqBridge = () => router.push('/app/qq-bridge')
const goAbout = () => router.push('/app/about')
const goBack = () => {
  if (window.history.length > 1) router.back()
  else router.push('/app')
}
const isElectron = isElectronApp()

// ---- 诊断日志 ----
const exportingLogs = ref(false)
const exportLogs = async () => {
  exportingLogs.value = true
  try {
    const ret = await getElectronAPI()?.exportLogs?.()
    if (ret?.ok) {
      ElMessage.success(`日志已导出（${(ret.bytes / 1024).toFixed(1)} KB）`)
    } else if (ret?.reason === 'cancelled') {
      // 用户取消，不提示
    } else {
      ElMessage.error(`导出失败：${ret?.error || ret?.reason || '未知错误'}`)
    }
  } catch (e) {
    ElMessage.error(`导出失败：${e?.message || e}`)
  } finally {
    exportingLogs.value = false
  }
}
const openLogFolder = () => {
  getElectronAPI()?.openLogFolder?.()
}
const petCatalog = PET_CATALOG.map(p => ({ ...p, previewUrl: getPetPreviewUrl(p) }))
const desktopForm = reactive({
  closeToTray: true,
  showDesktopPet: true,
  allowScreenObserve: false,
  launchAtLogin: false,
  launcherPetId: 'raiden',
})
const dialogVisible = ref(false)
const dialogWidth = useResponsiveDialogWidth(480)
const editingVault = ref(null)
const submitting = ref(false)
const formRef = ref(null)
const dialogModels = ref([])
const dialogModelsLoading = ref(false)

const initialForm = () => ({
  provider: '',
  apiKey: '',
  baseUrl: '',
  modelDefault: ''
})

const form = reactive(initialForm())

const isOllamaProvider = computed(() => {
  const url = form.baseUrl.trim().toLowerCase()
  return url.includes(':11434') || url.includes('ollama')
})

const apiKeyPlaceholder = computed(() => {
  if (editingVault.value) return '留空表示不修改原密钥'
  if (isOllamaProvider.value) return '本地 Ollama 可留空'
  return '填写服务商提供的 API Key'
})

function validateHttpUrl(_rule, value, callback) {
  const trimmed = (value || '').trim()
  if (!trimmed) {
    callback(new Error('请输入 Base URL'))
    return
  }
  if (!/^https?:\/\/.+/i.test(trimmed)) {
    callback(new Error('Base URL 须以 http:// 或 https:// 开头'))
    return
  }
  try {
    new URL(trimmed)
    callback()
  } catch {
    callback(new Error('Base URL 格式无效'))
  }
}

const formRules = computed(() => ({
  baseUrl: [{ required: true, validator: validateHttpUrl, trigger: 'blur' }],
  modelDefault: [{ required: true, message: '请输入默认模型', trigger: 'blur' }],
  apiKey:
    !editingVault.value && !isOllamaProvider.value
      ? [{ required: true, message: '请输入 API Key', trigger: 'blur' }]
      : []
}))

onMounted(async () => {
  providersStore.fetchVaults()
  if (isElectron) {
    await desktopStore.syncFromMain()
    desktopForm.closeToTray = desktopStore.closeToTray
    desktopForm.showDesktopPet = desktopStore.showDesktopPet
    desktopForm.allowScreenObserve = desktopStore.allowScreenObserve
    desktopForm.launchAtLogin = desktopStore.launchAtLogin
    desktopForm.launcherPetId = desktopStore.launcherPetId
  }
})

function petDisplayName(pet) {
  const lang = settingsStore.uiLanguage
  if (lang === 'en') return pet.nameEn
  if (lang === 'ja') return pet.nameJa
  return pet.nameZh
}

async function selectPet(id) {
  if (desktopForm.launcherPetId === id) return
  desktopForm.launcherPetId = id
  await onDesktopChange()
  ElMessage.success('桌宠已切换')
}

async function onDesktopChange() {
  if (!desktopForm.showDesktopPet && desktopForm.allowScreenObserve) {
    desktopForm.allowScreenObserve = false
    getElectronAPI()?.stopDesktopObserver?.()
  }
  try {
    await desktopStore.persist({
      closeToTray: desktopForm.closeToTray,
      showDesktopPet: desktopForm.showDesktopPet,
      showLauncherLogo: desktopForm.showDesktopPet,
      allowScreenObserve: desktopForm.allowScreenObserve,
      launchAtLogin: desktopForm.launchAtLogin,
      launcherPetId: desktopForm.launcherPetId,
    })
    getElectronAPI()?.requestChromeSync?.()
  } catch (err) {
    ElMessage.error(err?.message || '桌面设置保存失败')
  }
}

async function onScreenObserveChange(enabled) {
  if (!enabled) {
    getElectronAPI()?.stopDesktopObserver?.()
    await onDesktopChange()
    return
  }
  try {
    await ElMessageBox.confirm(
      '开启后，桌宠会定期截取整个屏幕和活动窗口标题，并上传到服务器进行 AI 识图分析，用于生成角色化问候语。截图仅用于实时分析，不会长期保存在服务器。\n\n你可以在设置中随时关闭此功能。',
      '屏幕观察授权',
      {
        confirmButtonText: '我已了解，开启',
        cancelButtonText: '取消',
        type: 'warning',
      }
    )
    await onDesktopChange()
  } catch {
    desktopForm.allowScreenObserve = false
  }
}

function showAddDialog() {
  editingVault.value = null
  Object.assign(form, initialForm())
  dialogModels.value = []
  dialogVisible.value = true
}

function showEditDialog(vault) {
  editingVault.value = vault
  form.provider = vault.provider
  form.apiKey = ''
  form.baseUrl = vault.baseUrl || ''
  form.modelDefault = vault.modelDefault || ''
  dialogModels.value = []
  dialogVisible.value = true
}

async function handleSubmit() {
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return

  submitting.value = true
  try {
    const alias = form.provider.trim()
    if (alias && alias.toLowerCase() === 'platform') {
      ElMessage.warning('不能使用保留别名 platform')
      submitting.value = false
      return
    }
    const data = {
      ...(alias ? { provider: alias } : {}),
      apiKey: form.apiKey?.trim() || (isOllamaProvider.value ? 'local' : ''),
      baseUrl: form.baseUrl.trim(),
      modelDefault: form.modelDefault.trim(),
    }

    if (editingVault.value) {
      const update = { baseUrl: data.baseUrl, modelDefault: data.modelDefault }
      if (form.apiKey?.trim()) {
        update.apiKey = form.apiKey.trim()
      }
      await providersStore.editVault(editingVault.value.id, update)
      ElMessage.success('Provider 已更新')
    } else {
      await providersStore.addVault(data)
      ElMessage.success('Provider 已添加')
    }
    dialogVisible.value = false
  } catch (err) {
    // Handled by interceptor
  } finally {
    submitting.value = false
  }
}

async function confirmDelete(vault) {
  try {
    await ElMessageBox.confirm(
      `确定要删除 "${vault.provider}" 的配置吗？此操作不可撤销。`,
      '删除确认',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
    await providersStore.removeVault(vault.id)
    ElMessage.success('Provider 已删除')
  } catch {}
}

async function handleFetchDialogModels() {
  const baseUrl = form.baseUrl.trim()
  if (!baseUrl) {
    ElMessage.warning('请先填写接口地址')
    return
  }
  const apiKey = form.apiKey?.trim()
  dialogModelsLoading.value = true
  try {
    let models
    if (editingVault.value && !apiKey) {
      // 编辑模式且未改密钥：用已保存凭据拉取
      models = await providersStore.fetchModelsFor(editingVault.value.provider)
    } else {
      // 添加模式 / 编辑时填了新密钥：用表单值预览
      if (!apiKey && !isOllamaProvider.value) {
        ElMessage.warning('请先填写 API Key')
        dialogModelsLoading.value = false
        return
      }
      models = await providersStore.previewModelsFor(baseUrl, apiKey || 'local')
    }
    dialogModels.value = models || []
    if (dialogModels.value.length) {
      ElMessage.success(`获取到 ${dialogModels.value.length} 个模型`)
    } else {
      ElMessage.warning('未获取到模型，请检查接口地址和密钥')
    }
  } catch (e) {
    ElMessage.error(`拉取模型失败：${e?.message || '请检查接口地址和密钥后重试'}`)
  } finally {
    dialogModelsLoading.value = false
  }
}
</script>

<style lang="scss" scoped>
.settings-page {
  max-width: $narrow-page-max;
}

.page-back {
  display: inline-flex;
  align-items: center;
  gap: $space-1;
  margin-bottom: $space-4;
  padding: $space-1 $space-3 $space-1 $space-2;
  border: 1px solid rgba($color-pink-rgb, 0.12);
  border-radius: $radius-pill;
  background: rgba(var(--ly-bg-surface-rgb), 0.35);
  color: $color-text-secondary;
  font-size: $font-size-sm;
  cursor: pointer;
  transition: all $transition-fast;
  &:hover { color: $color-pink-primary; border-color: rgba($color-pink-rgb, 0.35); }
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

.section {
  & + .section { margin-top: $space-12; }
}

.section-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  margin-bottom: $space-6;
}

.section-title {
  font-size: $font-size-lg;
  font-weight: $font-weight-semibold;
  color: $color-text-primary;
  margin-bottom: $space-1;
}

.field-hint {
  margin-top: $space-1;
  font-size: $font-size-xs;
  color: $color-text-muted;
  line-height: $line-height-normal;

  code {
    padding: 0 4px;
    border-radius: 4px;
    background: rgba($color-pink-rgb, 0.1);
    color: $color-pink-light;
    font-family: $font-mono;
    font-size: 0.9em;
  }
}

.section-desc {
  font-size: $font-size-sm;
  color: $color-text-muted;
}

// Empty state
.loading-state, .empty-state {
  text-align: center;
  padding: $space-16 $space-6;
  color: $color-text-muted;
}

.empty-icon {
  width: 80px; height: 80px;
  border-radius: $radius-xl;
  background: rgba($color-pink-rgb, 0.06);
  display: flex;
  align-items: center;
  justify-content: center;
  margin: 0 auto $space-6;
  color: $color-pink-primary;
}

.empty-state h3 { color: $color-text-primary; margin-bottom: $space-2; }
.empty-state p {
  color: $color-text-muted;
  font-size: $font-size-sm;
  margin-bottom: $space-6;
}

.desktop-section {
  margin-bottom: $space-8;
}

.desktop-settings {
  border-radius: $radius-lg;
  padding: $space-4 $space-5;
  display: flex;
  flex-direction: column;
  gap: $space-4;
}

.desktop-settings__row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: $space-4;
}

.desktop-settings__label {
  color: $color-text-primary;
  font-size: $font-size-sm;
  font-weight: $font-weight-medium;
}

.desktop-settings__hint {
  color: $color-text-muted;
  font-size: $font-size-xs;
  margin-top: 2px;
}

.desktop-settings__pet-block {
  padding-top: $space-4;
  border-top: 1px solid rgba(255, 255, 255, 0.06);
}

.pet-picker {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(88px, 1fr));
  gap: $space-3;
  margin-top: $space-4;
}

.pet-picker__item {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  padding: $space-2;
  border: 2px solid transparent;
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.03);
  cursor: pointer;
  transition: border-color 0.2s ease, background 0.2s ease, transform 0.15s ease;

  &:hover {
    background: rgba($color-pink-rgb, 0.08);
    transform: translateY(-2px);
  }

  &.is-active {
    border-color: rgba($color-pink-rgb, 0.65);
    background: rgba($color-pink-rgb, 0.12);
    box-shadow: 0 0 0 1px rgba($color-pink-rgb, 0.2);
  }
}

.pet-picker__img {
  width: 64px;
  height: 70px;
  object-fit: contain;
  image-rendering: pixelated;
}

.pet-picker__name {
  font-size: 11px;
  color: $color-text-secondary;
  text-align: center;
  line-height: 1.3;
}

// Provider grid
.provider-grid {
  display: grid;
  gap: $space-4;
}

.provider-card {
  border-radius: $radius-lg;
  padding: $space-5 $space-6;
  transition: all $transition-fast;

  &:hover {
    border-color: rgba($color-pink-rgb, 0.12);
    box-shadow: $shadow-glow-pink;
  }
}

.card-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: $space-4;
}

.provider-badge {
  display: inline-flex;
  align-items: center;
  padding: 3px 12px;
  border-radius: $radius-full;
  font-size: $font-size-xs;
  font-weight: $font-weight-semibold;
  text-transform: uppercase;
  letter-spacing: 0.05em;

  &.openai { background: rgba(#10a37f, 0.15); color: #10a37f; }
  &.deepseek { background: rgba($color-pink-rgb, 0.12); color: $color-pink-primary; }
  &.ollama { background: rgba($color-pink-light, 0.15); color: $color-pink-light; }
  &.gemini { background: rgba(#4285f4, 0.12); color: #8ab4f8; }
  &:not(.openai):not(.deepseek):not(.ollama):not(.gemini) {
    background: rgba($color-pink-rgb, 0.1);
    color: $color-pink-primary;
  }
}

.card-actions {
  display: flex;
  gap: $space-1;

  .btn-delete:hover { color: $color-error !important; }
}

.card-body {
  display: flex;
  flex-direction: column;
  gap: $space-2;
  margin-bottom: $space-4;
}

.info-row {
  display: flex;
  align-items: baseline;
  gap: $space-3;
}

.info-label {
  font-size: $font-size-xs;
  color: $color-text-muted;
  min-width: 72px;
  flex-shrink: 0;
}

.info-value {
  font-size: $font-size-sm;
  color: $color-text-secondary;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;

  &.mono {
    font-family: $font-mono;
    font-size: $font-size-xs;
    opacity: 0.6;
  }
}

.model-input-row {
  display: flex;
  gap: $space-2;
  width: 100%;
}

.model-select {
  flex: 1;
}
</style>
