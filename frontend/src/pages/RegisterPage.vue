<template>
  <div class="auth-page">
    <div class="bg-orb orb-1"></div>
    <div class="bg-orb orb-2"></div>

    <div class="auth-container">
      <div class="auth-art">
        <div class="art-content">
          <div class="art-icon">
            <span class="art-char">遇</span>
          </div>
          <h1 class="art-title">初次相遇</h1>
          <p class="art-subtitle">创建你的 LianYu 账号，开启你的故事</p>
        </div>
      </div>

      <div class="auth-form-panel">
        <h2 class="form-title">注册账号</h2>

        <el-form
          ref="formRef"
          :model="form"
          :rules="rules"
          class="auth-form"
          @submit.prevent="handleRegister"
        >
          <el-form-item prop="username">
            <el-input
              v-model="form.username"
              placeholder="用户名"
              :prefix-icon="User"
              autocomplete="username"
              class="auth-input"
            />
          </el-form-item>

          <el-form-item prop="nickname">
            <el-input
              v-model="form.nickname"
              placeholder="昵称（选填）"
              :prefix-icon="Sunny"
              class="auth-input"
            />
          </el-form-item>

          <el-form-item prop="password">
            <el-input
              v-model="form.password"
              type="password"
              placeholder="密码"
              :prefix-icon="Lock"
              show-password
              autocomplete="new-password"
              class="auth-input"
            />
          </el-form-item>

          <el-form-item prop="confirmPassword">
            <el-input
              v-model="form.confirmPassword"
              type="password"
              placeholder="确认密码"
              :prefix-icon="Lock"
              show-password
              autocomplete="new-password"
              class="auth-input"
              @keyup.enter="handleRegister"
            />
          </el-form-item>

          <!-- 验证码 -->
          <div class="captcha-row">
            <div class="captcha-card">
              <span class="captcha-label">验证码</span>
              <span class="captcha-expr">{{ captchaExpression }}</span>
              <button
                type="button"
                class="captcha-refresh"
                @click="refreshCaptcha"
                :disabled="loading"
              >
                <el-icon><Refresh /></el-icon>
              </button>
            </div>
            <el-input
              v-model="form.captchaAnswer"
              placeholder="输入答案"
              class="captcha-input"
              :disabled="loading"
            />
          </div>

          <button
            type="submit"
            class="submit-btn"
            :disabled="loading"
            @click="handleRegister"
          >
            <span v-if="loading" class="btn-loading">
              <el-icon class="is-loading"><Loading /></el-icon>
            </span>
            <span v-else>注 册</span>
          </button>
        </el-form>

        <div class="form-footer">
          <span class="footer-text">已有账号？</span>
          <router-link to="/login" class="footer-link">去登录</router-link>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '@/stores/user'
import { User, Lock, Sunny, Loading, Refresh } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { getCaptcha } from '@/api/auth'

const router = useRouter()
const userStore = useUserStore()
const formRef = ref(null)
const loading = ref(false)

const captchaId = ref('')
const captchaExpression = ref('加载中...')

const form = reactive({
  username: '',
  nickname: '',
  password: '',
  confirmPassword: '',
  captchaAnswer: ''
})

const validateConfirm = (rule, value, callback) => {
  if (value !== form.password) {
    callback(new Error('两次输入的密码不一致'))
  } else {
    callback()
  }
}

const rules = {
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    { min: 2, max: 64, message: '用户名长度 2-64 个字符', trigger: 'blur' }
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 6, message: '密码至少 6 位', trigger: 'blur' }
  ],
  confirmPassword: [
    { required: true, message: '请确认密码', trigger: 'blur' },
    { validator: validateConfirm, trigger: 'blur' }
  ]
}

onMounted(() => {
  refreshCaptcha()
})

async function refreshCaptcha() {
  try {
    const res = await getCaptcha()
    captchaId.value = res.captchaId
    captchaExpression.value = res.expression
  } catch {
    captchaExpression.value = '获取失败，点击刷新'
  }
}

async function handleRegister() {
  if (!captchaId.value) {
    ElMessage.warning('请等待验证码加载')
    return
  }
  if (!form.captchaAnswer) {
    ElMessage.warning('请输入验证码答案')
    return
  }
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return

  loading.value = true
  try {
    await userStore.register({
      username: form.username,
      password: form.password,
      nickname: form.nickname || undefined,
      captcha: {
        captchaId: captchaId.value,
        captchaAnswer: Number(form.captchaAnswer)
      }
    })
    await userStore.login({
      username: form.username,
      password: form.password,
      captcha: {
        captchaId: captchaId.value,
        captchaAnswer: Number(form.captchaAnswer)
      }
    })
    ElMessage.success('注册成功')
    router.push('/app')
  } catch (err) {
    refreshCaptcha()
    form.captchaAnswer = ''
  } finally {
    loading.value = false
  }
}
</script>

<style lang="scss" scoped>
.auth-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: $color-bg-deepest;
  position: relative;
  overflow: hidden;
  padding: $space-6;
}

// Ambient pink orbs
.bg-orb {
  position: absolute;
  border-radius: 50%;
  filter: blur(80px);
  pointer-events: none;
}
.orb-1 {
  width: 400px; height: 400px;
  background: rgba($color-pink-rgb, 0.06);
  top: -8%; right: -5%;
  animation: float 8s ease-in-out infinite;
}
.orb-2 {
  width: 350px; height: 350px;
  background: rgba($color-pink-light, 0.05);
  bottom: -10%; left: -5%;
  animation: float 10s ease-in-out infinite reverse;
}

.auth-container {
  display: flex;
  width: 880px;
  max-width: 100%;
  min-height: 560px;
  border-radius: $radius-xl;
  overflow: hidden;
  box-shadow: $shadow-lg, $shadow-glow-pink;
  position: relative;
  z-index: 1;
}

.auth-art {
  flex: 1;
  background: linear-gradient(135deg, $color-bg-surface 0%, $color-bg-secondary 100%);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: $space-12;
  position: relative;
  overflow: hidden;

  &::before {
    content: '';
    position: absolute;
    inset: 0;
    background:
      radial-gradient(circle at 70% 30%, rgba($color-pink-rgb, 0.08), transparent 60%),
      radial-gradient(circle at 30% 70%, rgba($color-pink-light, 0.05), transparent 50%);
  }
}

.art-content {
  text-align: center;
  position: relative;
  z-index: 1;
}

.art-icon {
  width: 80px; height: 80px;
  border-radius: $radius-xl;
  background: linear-gradient(135deg, $color-pink-primary, $color-pink-dark);
  display: flex;
  align-items: center;
  justify-content: center;
  margin: 0 auto $space-8;
  box-shadow: $shadow-glow-pink;
  animation: pulseGlow 3s ease-in-out infinite;
}

.art-char {
  font-size: 2.5rem;
  color: $color-text-inverse;
  font-weight: $font-weight-bold;
}

.art-title {
  font-size: $font-size-3xl;
  color: $color-text-primary;
  letter-spacing: 0.06em;
  margin-bottom: $space-3;
  font-weight: $font-weight-bold;
}

.art-subtitle {
  font-size: $font-size-base;
  color: $color-text-secondary;
  letter-spacing: 0.04em;
}

.auth-form-panel {
  flex: 1;
  display: flex;
  flex-direction: column;
  justify-content: center;
  padding: $space-12 $space-10;
  min-width: 360px;
  background: $color-bg-primary;
}

.form-title {
  font-size: $font-size-2xl;
  font-weight: $font-weight-bold;
  color: $color-text-primary;
  margin-bottom: $space-8;
}

// ---- Captcha Row ----
.captcha-row {
  display: flex;
  align-items: center;
  gap: $space-3;
  margin-bottom: $space-4;
}

.captcha-card {
  flex: 1;
  display: flex;
  align-items: center;
  gap: $space-2;
  background: #f8f8f8;
  border: 1px solid #e0e0e0;
  border-radius: $radius-md;
  padding: 0 12px;
  height: 44px;
}

.captcha-label {
  font-size: $font-size-xs;
  color: $color-text-muted;
  white-space: nowrap;
}

.captcha-expr {
  flex: 1;
  font-size: $font-size-lg;
  font-weight: $font-weight-bold;
  color: $color-pink-primary;
  text-align: center;
  letter-spacing: 0.08em;
  user-select: none;
}

.captcha-refresh {
  width: 32px;
  height: 32px;
  border: none;
  background: transparent;
  color: $color-text-secondary;
  cursor: pointer;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: all $transition-fast;
  flex-shrink: 0;

  &:hover { color: $color-pink-primary; background: rgba($color-pink-rgb, 0.08); }
  &:disabled { opacity: 0.4; cursor: not-allowed; }
}

.captcha-input {
  width: 100px;
  flex-shrink: 0;

  :deep(.el-input__wrapper) {
    background: #ffffff !important;
    border: 1px solid #d0d0d0 !important;
    border-radius: $radius-md !important;
    box-shadow: 0 1px 4px rgba(0, 0, 0, 0.06) !important;
    height: 44px !important;
    transition: border-color $transition-fast;

    &:hover { border-color: $color-pink-primary !important; }
    &.is-focus {
      border-color: $color-pink-primary !important;
      box-shadow: 0 0 0 3px rgba($color-pink-rgb, 0.12) !important;
    }
  }

  :deep(.el-input__inner) {
    color: #1a1a1a !important;
    font-size: $font-size-base !important;
    text-align: center;
    &::placeholder { color: #a0a0a0 !important; }
  }
}

// ---- Auth Inputs: white field with pink focus ----
.auth-form {
  :deep(.el-form-item) { margin-bottom: $space-5; }
}

.auth-input {
  :deep(.el-input__wrapper) {
    background: #ffffff !important;
    border: 1px solid #d0d0d0 !important;
    border-radius: $radius-md !important;
    box-shadow: 0 1px 4px rgba(0, 0, 0, 0.06) !important;
    padding: 2px 12px !important;
    height: 44px !important;
    transition: border-color $transition-fast, box-shadow $transition-fast;

    &:hover { border-color: $color-pink-primary !important; }
    &.is-focus {
      border-color: $color-pink-primary !important;
      box-shadow: 0 0 0 3px rgba($color-pink-rgb, 0.12) !important;
    }
  }

  :deep(.el-input__inner) {
    color: #1a1a1a !important;
    font-size: $font-size-base !important;
    line-height: 40px !important;
    height: 40px !important;
    &::placeholder { color: #a0a0a0 !important; }
  }

  :deep(.el-input__prefix) {
    color: #666;
    display: flex; align-items: center;
    margin-right: 6px;
    .el-icon, svg { width: 18px !important; height: 18px !important; font-size: 18px !important; }
  }

  :deep(.el-input__suffix) {
    color: #666;
    display: flex; align-items: center;
    .el-icon, svg { width: 18px !important; height: 18px !important; font-size: 18px !important; }
  }

  :deep(.el-input__suffix-inner) { display: flex; align-items: center; }
}

// ---- Submit Button: pink pill (matching Android) ----
.submit-btn {
  width: 100%;
  height: 48px;
  margin-top: $space-4;
  font-size: $font-size-base;
  font-weight: $font-weight-semibold;
  letter-spacing: 0.12em;
  border: none;
  border-radius: $radius-pill;
  cursor: pointer;
  background: linear-gradient(135deg, $color-pink-primary, $color-pink-dark);
  color: $color-text-inverse;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: all $transition-fast;

  &:hover {
    background: linear-gradient(135deg, $color-pink-light, $color-pink-primary);
    transform: translateY(-1px);
    box-shadow: 0 4px 20px rgba($color-pink-rgb, 0.35);
  }

  &:active {
    transform: translateY(0);
    box-shadow: none;
  }

  &:disabled {
    background: $color-pink-muted;
    cursor: not-allowed;
    transform: none;
    box-shadow: none;
  }
}

.btn-loading {
  display: flex;
  align-items: center;
  justify-content: center;
  .el-icon { color: $color-text-inverse; font-size: 20px; }
}

.form-footer {
  text-align: center;
  margin-top: $space-8;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: $space-2;
}

.footer-text { font-size: $font-size-sm; color: $color-text-muted; }
.footer-link {
  font-size: $font-size-sm;
  color: $color-pink-primary;
  font-weight: $font-weight-medium;
  &:hover { color: $color-pink-light; }
}

@media (max-width: 768px) {
  .auth-container { flex-direction: column; min-height: auto; }
  .auth-art {
    padding: $space-8;
    .art-icon { width: 56px; height: 56px; margin-bottom: $space-4; }
    .art-char { font-size: 1.75rem; }
    .art-title { font-size: $font-size-xl; }
  }
  .auth-form-panel { padding: $space-8 $space-6; min-width: auto; }
}
</style>