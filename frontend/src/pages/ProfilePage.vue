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
            <span class="avatar-hint">支持 JPG / PNG / WebP / GIF，最大 8MB</span>
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

    <section id="password" class="profile-card glass stagger-item password-card">
      <div class="password-card__head">
        <h2 class="section-title">账号安全</h2>
        <p class="section-desc">修改登录密码后，其它设备可能需要重新登录。</p>
      </div>
      <el-form
        ref="passwordFormRef"
        :model="passwordForm"
        :rules="passwordRules"
        label-position="top"
        class="password-form"
        @submit.prevent="handleChangePassword"
      >
        <el-form-item label="当前密码" prop="oldPassword">
          <el-input
            v-model="passwordForm.oldPassword"
            type="password"
            show-password
            autocomplete="current-password"
          />
        </el-form-item>
        <div class="form-grid">
          <el-form-item label="新密码" prop="newPassword">
            <el-input
              v-model="passwordForm.newPassword"
              type="password"
              show-password
              autocomplete="new-password"
            />
          </el-form-item>
          <el-form-item label="确认新密码" prop="confirmPassword">
            <el-input
              v-model="passwordForm.confirmPassword"
              type="password"
              show-password
              autocomplete="new-password"
            />
          </el-form-item>
        </div>
        <div class="form-actions">
          <el-button type="primary" class="btn-cta" :loading="changingPassword" @click="handleChangePassword">
            更新密码
          </el-button>
        </div>
      </el-form>
    </section>
  </div>
</template>

<script setup>
import { computed, nextTick, onMounted, onActivated, onDeactivated, onUnmounted, reactive, ref, watch } from 'vue'
defineOptions({ name: 'ProfilePage' })
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { UploadFilled, UserFilled } from '@element-plus/icons-vue'
import { useUserStore } from '@/stores/user'
import { resolveMediaUrl } from '@/utils/media'

const userStore = useUserStore()
const route = useRoute()
const formRef = ref(null)
const passwordFormRef = ref(null)
const fileInput = ref(null)
const savingProfile = ref(false)
const changingPassword = ref(false)
const uploadingAvatar = ref(false)
const isDragging = ref(false)
const localPreviewUrl = ref('')

const form = reactive({
  nickname: userStore.nickname || ''
})

const passwordForm = reactive({
  oldPassword: '',
  newPassword: '',
  confirmPassword: ''
})

watch(
  () => userStore.nickname,
  value => {
    form.nickname = value || ''
  },
  { immediate: true }
)

const previewUrl = computed(() => {
  const raw = localPreviewUrl.value || userStore.avatarUrl || ''
  return raw.startsWith('blob:') ? raw : resolveMediaUrl(raw)
})

const rules = {
  nickname: [
    { required: true, message: '请输入昵称', trigger: 'blur' },
    { max: 128, message: '昵称不能超过 128 个字符', trigger: 'blur' }
  ]
}

const validateConfirmPassword = (_rule, value, callback) => {
  if (value !== passwordForm.newPassword) {
    callback(new Error('两次输入的新密码不一致'))
    return
  }
  callback()
}

const passwordRules = {
  oldPassword: [{ required: true, message: '请输入当前密码', trigger: 'blur' }],
  newPassword: [
    { required: true, message: '请输入新密码', trigger: 'blur' },
    { min: 6, max: 128, message: '密码长度 6–128 位', trigger: 'blur' }
  ],
  confirmPassword: [
    { required: true, message: '请再次输入新密码', trigger: 'blur' },
    { validator: validateConfirmPassword, trigger: 'blur' }
  ]
}

onMounted(async () => {
  try {
    await userStore.fetchProfile()
  } catch {
    // ignore
  }
  if (route.hash === '#password') {
    await nextTick()
    document.getElementById('password')?.scrollIntoView({ behavior: 'smooth', block: 'start' })
  }
})

let firstActivation = true
onActivated(() => {
  if (firstActivation) { firstActivation = false; return }
  userStore.fetchProfile().catch(() => {})
})

onDeactivated(() => {
  passwordForm.oldPassword = ''
  passwordForm.newPassword = ''
  passwordForm.confirmPassword = ''
  passwordFormRef.value?.clearValidate()
})

onUnmounted(revokeLocalPreview)

function triggerUpload() {
  fileInput.value?.click()
}

/** 释放本地头像预览的 blob: 对象 URL（仅对本地预览生效，服务器 URL 不动）。
 *  见 issue #17：createObjectURL 不 revoke 会持续占用 blob 引用直到页面卸载 */
function revokeLocalPreview() {
  if (localPreviewUrl.value?.startsWith('blob:')) {
    URL.revokeObjectURL(localPreviewUrl.value)
    localPreviewUrl.value = ''
  }
}

function applyAvatarFile(file) {
  if (!file.type.startsWith('image/')) {
    ElMessage.warning('请上传图片文件（JPG / PNG / WebP）')
    return
  }
  if (file.size > 8 * 1024 * 1024) {
    ElMessage.warning('图片大小不能超过 8MB')
    return
  }
  revokeLocalPreview()
  localPreviewUrl.value = URL.createObjectURL(file)
  uploadAvatar(file)
}

async function uploadAvatar(file) {
  uploadingAvatar.value = true
  try {
    await userStore.uploadAvatar(file)
    revokeLocalPreview()
    ElMessage.success('头像已更新')
  } finally {
    uploadingAvatar.value = false
  }
}

onUnmounted(() => {
  revokeLocalPreview()
})

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

async function handleChangePassword() {
  const valid = await passwordFormRef.value?.validate().catch(() => false)
  if (!valid) return
  changingPassword.value = true
  try {
    await userStore.changePassword({
      oldPassword: passwordForm.oldPassword,
      newPassword: passwordForm.newPassword
    })
    passwordForm.oldPassword = ''
    passwordForm.newPassword = ''
    passwordForm.confirmPassword = ''
    passwordFormRef.value?.clearValidate()
    ElMessage.success('密码已更新')
  } finally {
    changingPassword.value = false
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

.password-card {
  margin-top: $space-5;
}

.password-card__head {
  margin-bottom: $space-4;
}

.section-title {
  font-size: $font-size-lg;
  font-weight: $font-weight-semibold;
  color: $color-text-primary;
  margin-bottom: $space-2;
}

.section-desc {
  font-size: $font-size-sm;
  color: $color-text-muted;
}

.password-form {
  max-width: 640px;
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
