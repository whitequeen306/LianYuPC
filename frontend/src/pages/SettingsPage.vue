<template>
  <div class="settings-page stagger-container">
    <header class="page-header">
      <h1 class="page-title">{{ t('settings.title') }}</h1>
      <p class="page-desc">{{ t('settings.desc') }}</p>
    </header>

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

          <div class="card-footer">
            <el-button
              text
              size="small"
              :icon="RefreshRight"
              :loading="modelsLoading[vault.provider]"
              @click="handleFetchModels(vault.provider)"
            >
              获取模型列表
            </el-button>
            <span v-if="providerModels[vault.provider]?.length" class="model-count">
              {{ providerModels[vault.provider].length }} 个模型可用
            </span>
          </div>

          <div v-if="providerModels[vault.provider]?.length" class="model-list">
            <el-tag
              v-for="m in (providerModels[vault.provider] || []).slice(0, 8)"
              :key="m.id"
              size="small"
              type="info"
              effect="dark"
            >
              {{ m.name || m.id }}
            </el-tag>
            <span v-if="providerModels[vault.provider].length > 8" class="more-models">
              +{{ providerModels[vault.provider].length - 8 }}
            </span>
          </div>
        </div>
      </div>
    </section>

    <!-- Add/Edit Dialog -->
    <el-dialog
      v-model="dialogVisible"
      :title="editingVault ? '编辑 AI 配置' : '添加 AI 配置'"
      width="480px"
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
          <el-input
            v-model="form.modelDefault"
            placeholder="例如: deepseek-chat、qwen-max"
          />
          <p class="field-hint">使用自定义接口时必须指定模型名称。</p>
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
import { useI18n } from 'vue-i18n'
import { useProvidersStore } from '@/stores/providers'

const { t } = useI18n()
import { Plus, Edit, Delete, RefreshRight, Loading, Connection } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'

const providersStore = useProvidersStore()
const dialogVisible = ref(false)
const editingVault = ref(null)
const submitting = ref(false)
const formRef = ref(null)
const modelsLoading = ref({})
const providerModels = ref({})

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

onMounted(() => {
  providersStore.fetchVaults()
})

function showAddDialog() {
  editingVault.value = null
  Object.assign(form, initialForm())
  dialogVisible.value = true
}

function showEditDialog(vault) {
  editingVault.value = vault
  form.provider = vault.provider
  form.apiKey = vault.apiKey || ''
  form.baseUrl = vault.baseUrl || ''
  form.modelDefault = vault.modelDefault || ''
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

async function handleFetchModels(provider) {
  modelsLoading.value[provider] = true
  try {
    const models = await providersStore.fetchModelsFor(provider)
    providerModels.value[provider] = models
  } catch {
    // Handled by interceptor
  } finally {
    modelsLoading.value[provider] = false
  }
}
</script>

<style lang="scss" scoped>
.settings-page {
  max-width: 780px;
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

.card-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding-top: $space-3;
  border-top: 1px solid rgba($color-pink-rgb, 0.06);
}

.model-count {
  font-size: $font-size-xs;
  color: $color-text-muted;
}

.model-list {
  display: flex;
  flex-wrap: wrap;
  gap: $space-2;
  margin-top: $space-3;
  padding-top: $space-3;
  border-top: 1px solid rgba($color-pink-rgb, 0.06);
}

.more-models {
  font-size: $font-size-xs;
  color: $color-text-muted;
  display: flex;
  align-items: center;
}
</style>
