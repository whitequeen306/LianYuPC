<template>
  <div ref="pageRef" class="auth-page auth-page--register">
    <div class="auth-page__mesh" aria-hidden="true" />
    <div class="auth-page__grain" aria-hidden="true" />
    <div class="auth-page__grid" aria-hidden="true" />
    <div class="bg-orb orb-1" aria-hidden="true" />
    <div class="bg-orb orb-2" aria-hidden="true" />
    <div class="bg-orb orb-3" aria-hidden="true" />
    <AuthParticles />

    <div class="auth-container">
      <div class="auth-art">
        <div class="art-ring" aria-hidden="true" />
        <div class="art-content">
          <div class="art-icon">
            <img :src="APP_LOGO" alt="LianYu" class="art-logo" />
          </div>
          <h1 class="art-title">初次相遇</h1>
          <p class="art-subtitle">创建你的 LianYu 账号，开启你的故事</p>
          <div class="art-decorations">
            <span class="deco-dot" />
            <span class="deco-line" />
            <span class="deco-dot" />
          </div>
          <p class="art-tagline">角色广场 · 长期记忆 · 情感陪伴</p>
        </div>
      </div>

      <div class="auth-form-panel">
        <div class="form-header form-reveal">
          <h2 class="form-title">注册账号</h2>
          <p class="form-desc">填写信息即可开始与角色对话</p>
        </div>

        <el-form
          ref="formRef"
          :model="form"
          :rules="rules"
          class="auth-form form-reveal"
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
              placeholder="密码（至少 6 位）"
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
            />
          </el-form-item>

          <div class="captcha-row form-reveal">
            <div class="captcha-card">
              <span class="captcha-label">验证码</span>
              <img
                v-if="captchaImageSrc"
                :src="captchaImageSrc"
                alt="验证码"
                class="captcha-image"
                @click="refreshCaptcha"
                @error="onCaptchaImgError"
              />
              <span v-else class="captcha-expr">{{ captchaHint }}</span>
              <button
                type="button"
                class="captcha-refresh"
                :disabled="loading"
                @click="refreshCaptcha"
              >
                <el-icon><Refresh /></el-icon>
              </button>
            </div>
            <el-input
              v-model="form.captchaAnswer"
              placeholder="答案"
              class="captcha-input"
              :disabled="loading"
            />
          </div>

          <button
            type="submit"
            class="submit-btn form-reveal"
            :disabled="loading"
          >
            <span v-if="loading" class="btn-loading">
              <el-icon class="is-loading"><Loading /></el-icon>
            </span>
            <span v-else>注 册</span>
            <span class="submit-btn__shine" aria-hidden="true" />
          </button>
        </el-form>

        <div class="form-footer form-reveal">
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
import { APP_LOGO } from '@/constants/brand.js'
import { ElMessage } from 'element-plus'
import { getCaptcha } from '@/api/auth'
import AuthParticles from '@/components/auth/AuthParticles.vue'
import { useAuthPageGsap } from '@/composables/useAuthPageGsap'

const router = useRouter()
const userStore = useUserStore()
const formRef = ref(null)
const pageRef = ref(null)
const loading = ref(false)

useAuthPageGsap(pageRef)

const captchaId = ref('')
const captchaImageSrc = ref('')
const captchaHint = ref('加载中...')

function applyCaptchaResponse(res) {
  captchaId.value = res?.captchaId ?? ''
  const b64 = typeof res?.imageBase64 === 'string' ? res.imageBase64.trim() : ''
  if (b64) {
    captchaImageSrc.value = b64.startsWith('data:') ? b64 : `data:image/png;base64,${b64}`
    captchaHint.value = ''
    return
  }
  captchaImageSrc.value = ''
  captchaHint.value = '获取失败，点击刷新'
}

function onCaptchaImgError() {
  captchaImageSrc.value = ''
  captchaHint.value = '验证码加载失败，点击刷新'
}

const form = reactive({
  username: '',
  nickname: '',
  password: '',
  confirmPassword: '',
  captchaAnswer: '',
})

const validateConfirm = (_rule, value, callback) => {
  if (value !== form.password) {
    callback(new Error('两次输入的密码不一致'))
  } else {
    callback()
  }
}

const rules = {
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    { min: 2, max: 64, message: '用户名长度 2-64 个字符', trigger: 'blur' },
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 6, message: '密码至少 6 位', trigger: 'blur' },
  ],
  confirmPassword: [
    { required: true, message: '请确认密码', trigger: 'blur' },
    { validator: validateConfirm, trigger: 'blur' },
  ],
}

onMounted(() => {
  refreshCaptcha()
})

async function refreshCaptcha() {
  try {
    const res = await getCaptcha()
    applyCaptchaResponse(res)
  } catch {
    captchaImageSrc.value = ''
    captchaHint.value = '获取失败，点击刷新'
    ElMessage.error('无法连接服务器，请确认网络正常且后端已启动')
  }
}

async function handleRegister() {
  if (loading.value) return
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
        captchaAnswer: Number(form.captchaAnswer),
      },
    })
    ElMessage.success('注册成功')
    router.push('/app')
  } catch {
    refreshCaptcha()
    form.captchaAnswer = ''
  } finally {
    loading.value = false
  }
}
</script>

<style lang="scss">
@import url('https://fonts.googleapis.com/css2?family=Noto+Serif+SC:wght@500;600;700&family=Syne:wght@500;600;700&display=swap');
</style>

<style lang="scss" scoped>
@use '@/styles/auth-page.scss';
</style>
