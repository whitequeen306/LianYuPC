<template>
  <div ref="pageRef" class="auth-page">
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
          <h1 class="art-title">LianYu</h1>
          <p class="art-subtitle">与心之所向，促膝长谈</p>
          <div class="art-decorations">
            <span class="deco-dot" />
            <span class="deco-line" />
            <span class="deco-dot" />
          </div>
          <p class="art-tagline">虚拟恋人 · 流式对话 · 长期记忆</p>
        </div>
      </div>

      <div class="auth-form-panel">
        <div class="form-header form-reveal">
          <h2 class="form-title">欢迎回来</h2>
          <p class="form-desc">登录你的 LianYu 账号继续对话</p>
        </div>

        <el-form
          ref="formRef"
          :model="form"
          :rules="rules"
          class="auth-form form-reveal"
          @submit.prevent="handleLogin"
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

          <el-form-item prop="password">
            <el-input
              v-model="form.password"
              type="password"
              placeholder="密码"
              :prefix-icon="Lock"
              show-password
              autocomplete="current-password"
              class="auth-input"
              @keyup.enter="handleLogin"
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
            @click="handleLogin"
          >
            <span v-if="loading" class="btn-loading">
              <el-icon class="is-loading"><Loading /></el-icon>
            </span>
            <span v-else class="submit-btn__text">登 录</span>
            <span class="submit-btn__shine" aria-hidden="true" />
          </button>
        </el-form>

        <div class="form-footer form-reveal">
          <span class="footer-text">还没有账号？</span>
          <router-link to="/register" class="footer-link">立即注册</router-link>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '@/stores/user'
import { User, Lock, Loading, Refresh } from '@element-plus/icons-vue'
import { APP_LOGO } from '@/constants/brand.js'
import { ElMessage } from 'element-plus'
import { getCaptcha } from '@/api/auth'
import { getLastUsername } from '@/stores/user'
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

const form = reactive({
  username: '',
  password: '',
  captchaAnswer: '',
})

const rules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
}

onMounted(() => {
  form.username = getLastUsername()
  refreshCaptcha()
})

async function refreshCaptcha() {
  try {
    const res = await getCaptcha()
    captchaId.value = res.captchaId
    captchaImageSrc.value = res.imageBase64 ? `data:image/png;base64,${res.imageBase64}` : ''
    captchaHint.value = captchaImageSrc.value ? '' : '获取失败，点击刷新'
  } catch {
    captchaImageSrc.value = ''
    captchaHint.value = '获取失败，点击刷新'
    ElMessage.error('无法连接服务器，请确认网络正常且后端已启动')
  }
}

async function handleLogin() {
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
    await userStore.login({
      username: form.username,
      password: form.password,
      captcha: {
        captchaId: captchaId.value,
        captchaAnswer: Number(form.captchaAnswer),
      },
    })
    ElMessage.success('登录成功')
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
