<template>
  <el-dialog
    v-model="visible"
    class="character-dialog"
    :title="t('characters.dialogImport')"
    :width="dialogWidth"
    destroy-on-close
    @closed="resetForm"
  >
    <p class="import-lead">{{ t('characters.importHint') }}</p>
    <el-form
      ref="formRef"
      :model="form"
      :rules="formRules"
      label-position="top"
      @submit.prevent="handleImport"
    >
      <el-form-item :label="t('characters.importSource')" prop="sourceText">
        <el-input
          v-model="form.sourceText"
          type="textarea"
          :rows="7"
          :placeholder="t('characters.importSourcePlaceholder')"
        />
        <div class="source-actions">
          <el-button type="default" size="small" @click="triggerSourceUpload">
            {{ t('characters.importPickFile') }}
          </el-button>
          <span v-if="sourceFileName" class="source-filename">{{ sourceFileName }}</span>
        </div>
        <div class="field-hint">{{ t('characters.importSourceHint') }}</div>
        <input
          ref="sourceInput"
          type="file"
          accept=".txt,.md,.json,.html,.htm,.csv,.log,text/plain,application/json,text/html,text/markdown"
          style="display:none"
          @change="handleSourceFile"
        />
      </el-form-item>

      <CharacterCityModeForm v-model:city="form.city" />

      <el-form-item :label="t('characters.importAddressing')" prop="userAddressing">
        <el-input
          v-model="form.userAddressing"
          :placeholder="t('characters.importAddressingPlaceholder')"
          :maxlength="ADDRESSING_MAX_CHARS"
          show-word-limit
        />
        <div class="field-hint">{{ t('characters.importAddressingHint') }}</div>
      </el-form-item>

      <el-form-item :label="t('characters.importAvatar')">
        <div class="avatar-upload">
          <div class="avatar-preview" @click="triggerAvatarUpload">
            <img v-if="form.avatarUrl" :src="form.avatarUrl" class="preview-img" />
            <template v-else>
              <el-icon :size="28"><UploadFilled /></el-icon>
              <span class="drop-hint">{{ t('characters.importPickAvatar') }}</span>
            </template>
          </div>
          <div class="avatar-actions">
            <el-button type="default" size="small" @click="triggerAvatarUpload">
              {{ form.avatarUrl ? t('characters.importChangeAvatar') : t('characters.importPickAvatar') }}
            </el-button>
            <span class="avatar-hint">{{ t('characters.importAvatarHint') }}</span>
          </div>
          <input
            ref="avatarInput"
            type="file"
            accept="image/jpeg,image/png,image/webp,image/gif"
            style="display:none"
            @change="handleAvatarFile"
          />
        </div>
      </el-form-item>
    </el-form>

    <template #footer>
      <el-button type="default" @click="visible = false">{{ t('characters.cancel') }}</el-button>
      <el-button type="primary" class="btn-cta" :loading="submitting" @click="handleImport">
        {{ t('characters.import') }}
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { computed, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import { UploadFilled } from '@element-plus/icons-vue'
import { analyzeCharacterImport, uploadAvatar } from '@/api/character'
import { useCharactersStore } from '@/stores/characters'
import { useProvidersStore } from '@/stores/providers'
import { useResponsiveDialogWidth } from '@/composables/useResponsiveDialogWidth'
import { getSavedUserCity, saveUserCity } from '@/utils/userCity'
import {
  ADDRESSING_MAX_CHARS,
  IMPORT_MAX_RAW_CHARS,
  buildImportCreatePayload,
  isAllowedImportFile,
  readImportFileAsText,
  sanitizeAddressing,
} from '@/utils/characterImport'
import CharacterCityModeForm from '@/components/CharacterCityModeForm.vue'

const props = defineProps({
  modelValue: { type: Boolean, default: false }
})
const emit = defineEmits(['update:modelValue'])

const { t } = useI18n()
const dialogWidth = useResponsiveDialogWidth(560)
const charactersStore = useCharactersStore()
const providersStore = useProvidersStore()
const formRef = ref(null)
const sourceInput = ref(null)
const avatarInput = ref(null)
const submitting = ref(false)
const sourceFileName = ref('')
const avatarFile = ref(null)

const visible = computed({
  get: () => props.modelValue,
  set: (v) => emit('update:modelValue', v)
})

const initialForm = () => ({
  sourceText: '',
  city: getSavedUserCity(),
  userAddressing: '',
  avatarUrl: ''
})
const form = reactive(initialForm())

const formRules = computed(() => ({
  sourceText: [{ required: true, message: t('characters.importNeedSource'), trigger: 'blur' }],
  city: [{ required: true, message: t('cityMode.realCityLabel'), trigger: 'blur' }],
  userAddressing: [{ required: true, message: t('characters.importNeedAddressing'), trigger: 'blur' }]
}))

function resetForm() {
  revokeAvatarPreview()
  Object.assign(form, initialForm())
  sourceFileName.value = ''
  avatarFile.value = null
}

function revokeAvatarPreview() {
  if (form.avatarUrl && String(form.avatarUrl).startsWith('blob:')) {
    URL.revokeObjectURL(form.avatarUrl)
  }
}

function triggerSourceUpload() {
  sourceInput.value?.click()
}

function triggerAvatarUpload() {
  avatarInput.value?.click()
}

async function handleSourceFile(e) {
  const file = e.target.files?.[0]
  e.target.value = ''
  if (!file) return
  if (!isAllowedImportFile(file)) {
    ElMessage.warning(t('characters.importFileType'))
    return
  }
  try {
    const text = await readImportFileAsText(file)
    if (text.length > IMPORT_MAX_RAW_CHARS) {
      ElMessage.warning(t('characters.importFileTooLarge'))
      return
    }
    form.sourceText = text
    sourceFileName.value = file.name
  } catch (err) {
    if (err?.code === 'FILE_TOO_LARGE') {
      ElMessage.warning(t('characters.importFileTooLarge'))
      return
    }
    ElMessage.warning(t('characters.importFileType'))
  }
}

function handleAvatarFile(e) {
  const file = e.target.files?.[0]
  e.target.value = ''
  if (!file) return
  if (!file.type.startsWith('image/')) {
    ElMessage.warning('请上传图片文件（JPG / PNG / WebP）')
    return
  }
  if (file.size > 8 * 1024 * 1024) {
    ElMessage.warning('图片大小不能超过 8MB')
    return
  }
  revokeAvatarPreview()
  avatarFile.value = file
  form.avatarUrl = URL.createObjectURL(file)
}

async function handleImport() {
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return

  const sourceText = form.sourceText.trim()
  const userAddressing = sanitizeAddressing(form.userAddressing)
  const city = form.city.trim()
  if (!sourceText) {
    ElMessage.warning(t('characters.importNeedSource'))
    return
  }
  if (!userAddressing) {
    ElMessage.warning(t('characters.importNeedAddressing'))
    return
  }

  submitting.value = true
  try {
    await providersStore.fetchVaults().catch(() => {})
    if (!providersStore.textVaults.length) {
      ElMessage.warning(t('characters.importNeedModel'))
      return
    }

    const draft = await analyzeCharacterImport({ sourceText, userAddressing })
    const payload = buildImportCreatePayload({ draft, city, userAddressing })
    if (city) saveUserCity(city)

    let char = await charactersStore.create(payload)
    if (avatarFile.value && char?.id) {
      char = await uploadAvatar(char.id, avatarFile.value)
      charactersStore.upsertLocal(char)
    }
    ElMessage.success(t('characters.importSuccess', { name: char?.name || payload.name }))
    visible.value = false
  } catch {
    // 全局拦截器已提示
  } finally {
    submitting.value = false
  }
}
</script>

<style lang="scss" scoped>
.import-lead {
  margin: 0 0 $space-4;
  font-size: $font-size-sm;
  color: $color-text-secondary;
  line-height: $line-height-relaxed;
}

.field-hint {
  margin-top: $space-2;
  font-size: $font-size-xs;
  color: $color-text-muted;
  line-height: $line-height-relaxed;
}

.source-actions {
  display: flex;
  align-items: center;
  gap: $space-3;
  margin-top: $space-3;
}

.source-filename {
  font-size: $font-size-xs;
  color: $color-text-secondary;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.avatar-upload {
  display: flex;
  align-items: center;
  gap: $space-4;
}

.avatar-preview {
  width: 88px;
  height: 88px;
  border-radius: $radius-lg;
  background: $color-bg-secondary;
  border: 1px dashed rgba($color-pink-rgb, 0.28);
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: $space-1;
  cursor: pointer;
  overflow: hidden;
  color: $color-text-muted;
  transition: border-color 0.24s cubic-bezier(0.23, 1, 0.32, 1);
}

.avatar-preview:hover {
  border-color: $color-pink-primary;
}

.drop-hint {
  font-size: $font-size-xs;
}

.preview-img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.avatar-actions {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: $space-2;
}

.avatar-hint {
  font-size: $font-size-xs;
  color: $color-text-secondary;
  line-height: $line-height-relaxed;
}
</style>
