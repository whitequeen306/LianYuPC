<template>
  <div class="profile-page stagger-container">
    <header class="page-header">
      <div>
        <h1 class="page-title">个人资料</h1>
        <p class="page-desc">管理你的头像和昵称，顶部栏会实时同步展示。</p>
      </div>
    </header>

    <section class="profile-card glass stagger-item">
      <div class="profile-avatar-section">
        <div
          class="avatar-upload"
          :class="{ 'is-dragging': isDragging }"
          @dragenter.prevent="isDragging = true"
          @dragover.prevent
          @dragleave.prevent="onDragLeave"
          @drop.prevent="onDrop"
        >
          <div class="avatar-preview" @click="triggerUpload">
            <img v-if="previewUrl" :src="previewUrl" class="preview-img" />
            <template v-else>
              <el-icon :size="32"><UserFilled /></el-icon>
              <span class="drop-hint">上传头像</span>
            </template>
            <div v-if="isDragging" class="drop-overlay">
              <el-icon :size="32"><UploadFilled /></el-icon>
              <span>松开上传</span>
            </div>
          </div>
          <div class="avatar-actions">
            <el-button type="default" size="small" :loading="uploadingAvatar" @click="triggerUpload">
              {{ previewUrl ? '更换头像' : '选择图片' }}
            </el-button>
            <span class="avatar-hint">支持 JPG / PNG / WebP / GIF，最大 5MB</span>
          </div>
          <input
            ref="fileInput"
            type="file"
            accept="image/jpeg,image/png,image/webp,image/gif"
            style="display:none"
            @change="handleFileChange"
          />
        </div>
      </div>

      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        label-position="top"
        class="profile-form"
        @submit.prevent="handleSave"
      >
        <div class="form-grid">
          <el-form-item label="用户名">
            <el-input :model-value="userStore.username" disabled />
          </el-form-item>
          <el-form-item label="昵称" prop="nickname">
            <el-input v-model="form.nickname" maxlength="128" show-word-limit placeholder="输入你的昵称" />
          </el-form-item>
        </div>

        <div class="form-actions">
          <el-button type="primary" class="btn-cta" :loading="savingProfile" @click="handleSave">
            保存资料
          </el-button>
        </div>
      </el-form>
    </section>
  </div>
</template>

<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { UploadFilled, UserFilled } from '@element-plus/icons-vue'
import { useUserStore } from '@/stores/user'

const userStore = useUserStore()
const formRef = ref(null)
const fileInput = ref(null)
const savingProfile = ref(false)
const uploadingAvatar = ref(false)
const isDragging = ref(false)
const localPreviewUrl = ref('')

const form = reactive({
  nickname: userStore.nickname || ''
})

watch(
  () => userStore.nickname,
  value => {
    form.nickname = value || ''
  },
  { immediate: true }
)

const previewUrl = computed(() => localPreviewUrl.value || userStore.avatarUrl || '')

const rules = {
  nickname: [
    { required: true, message: '请输入昵称', trigger: 'blur' },
    { max: 128, message: '昵称不能超过 128 个字符', trigger: 'blur' }
  ]
}

function triggerUpload() {
  fileInput.value?.click()
}

function applyAvatarFile(file) {
  if (!file.type.startsWith('image/')) {
    ElMessage.warning('请上传图片文件（JPG / PNG / WebP）')
    return
  }
  if (file.size > 5 * 1024 * 1024) {
    ElMessage.warning('图片大小不能超过 5MB')
    return
  }
  localPreviewUrl.value = URL.createObjectURL(file)
  uploadAvatar(file)
}

async function uploadAvatar(file) {
  uploadingAvatar.value = true
  try {
    await userStore.uploadAvatar(file)
    localPreviewUrl.value = ''
    ElMessage.success('头像已更新')
  } finally {
    uploadingAvatar.value = false
  }
}

function handleFileChange(e) {
  const file = e.target.files?.[0]
  if (file) applyAvatarFile(file)
  e.target.value = ''
}

function onDragLeave(e) {
  if (e.currentTarget?.contains(e.relatedTarget)) return
  isDragging.value = false
}

function onDrop(e) {
  isDragging.value = false
  const file = e.dataTransfer?.files?.[0]
  if (file) applyAvatarFile(file)
}

async function handleSave() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  savingProfile.value = true
  try {
    await userStore.updateProfile({ nickname: form.nickname.trim() })
    ElMessage.success('资料已保存')
  } finally {
    savingProfile.value = false
  }
}
</script>

<style lang="scss" scoped>
.profile-page {
  max-width: 880px;
}

.page-header {
  margin-bottom: $space-6;
  animation: fadeSlideUp 0.5s ease both;
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

.profile-card {
  border-radius: $radius-xl;
  padding: $space-6;
  display: grid;
  grid-template-columns: 280px 1fr;
  gap: $space-6;
}

.profile-avatar-section {
  display: flex;
  align-items: flex-start;
}

.avatar-upload {
  width: 100%;
}

.avatar-preview {
  position: relative;
  width: 220px;
  height: 220px;
  border-radius: $radius-xl;
  overflow: hidden;
  background: rgba($color-pink-rgb, 0.08);
  border: 1px dashed rgba($color-pink-rgb, 0.26);
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: $space-3;
  color: $color-pink-primary;
  cursor: pointer;
}

.preview-img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.drop-hint {
  font-size: $font-size-sm;
  color: $color-text-secondary;
}

.drop-overlay {
  position: absolute;
  inset: 0;
  background: rgba($color-bg-primary, 0.82);
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: $space-3;
  color: $color-pink-primary;
}

.avatar-actions {
  margin-top: $space-4;
  display: flex;
  flex-direction: column;
  gap: $space-2;
}

.avatar-hint {
  font-size: $font-size-sm;
  color: $color-text-muted;
}

.profile-form {
  display: flex;
  flex-direction: column;
  justify-content: center;
}

.form-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: $space-4;
}

.form-actions {
  margin-top: $space-4;
  display: flex;
  justify-content: flex-start;
}

@media (max-width: 900px) {
  .profile-card {
    grid-template-columns: 1fr;
  }

  .avatar-preview {
    width: 180px;
    height: 180px;
  }

  .form-grid {
    grid-template-columns: 1fr;
  }
}
</style>
