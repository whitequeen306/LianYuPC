<template>
  <div class="about-page stagger-container">
    <button class="page-back" type="button" @click="goBack">
      <el-icon><ArrowLeft /></el-icon>
      <span>返回</span>
    </button>
    <header class="page-header">
      <h1 class="page-title">{{ t('about.title') }}</h1>
      <p class="page-desc">{{ t('about.desc') }}</p>
    </header>

    <!-- 应用信息 -->
    <section class="section stagger-item">
      <div class="glass about-card">
        <div class="about-brand">
          <img :src="APP_LOGO" alt="LianYu" class="about-logo" @click="handleLogoClick" />
          <div class="about-brand__text">
            <div class="about-brand__name">恋语 <span class="about-brand__en">LianYu</span></div>
          </div>
        </div>
        <div class="about-info">
          <div class="info-row">
            <span class="info-label">{{ t('about.version') }}</span>
            <span class="info-value mono">v{{ version }}</span>
            <AppUpdateButton v-if="isElectron" />
          </div>
          <div class="info-row">
            <span class="info-label">{{ t('about.environment') }}</span>
            <span class="info-value">{{ isElectron ? t('about.envDesktop') : t('about.envWeb') }}</span>
          </div>
        </div>
        <p class="about-intro">{{ t('about.intro') }}</p>
      </div>
    </section>

    <!-- 版权 -->
    <section class="section stagger-item">
      <div class="glass about-card about-footer">
        <span class="about-copyright">{{ t('about.copyright') }}</span>
      </div>
    </section>

    <!-- 开发者 -->
    <section class="section stagger-item">
      <div class="section-header">
        <h2 class="section-title">开发者</h2>
      </div>
      <div class="glass about-card about-devs">
        <div class="dev-group">
          <h3 class="dev-role">核心主开发者</h3>
          <a :href="'https://github.com/whitequeen306'" target="_blank" rel="noopener noreferrer" class="dev-item">
            <span class="dev-name">青思雨</span>
            <el-icon class="dev-link-icon"><Link /></el-icon>
          </a>
        </div>
        <div class="dev-group">
          <h3 class="dev-role">开发者及 API 支持</h3>
          <a :href="'https://github.com/2164312714-svg'" target="_blank" rel="noopener noreferrer" class="dev-item">
            <span class="dev-name">Clove.</span>
            <el-icon class="dev-link-icon"><Link /></el-icon>
          </a>
        </div>
        <div class="dev-group">
          <h3 class="dev-role">其它鸣谢</h3>
          <p class="dev-item dev-thanks">恋语安卓端全体开发团队</p>
        </div>
      </div>
    </section>
  </div>
</template>

<script setup>
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { isElectronApp } from '@/utils/electron'
import AppUpdateButton from '@/components/AppUpdateButton.vue'
import { ArrowLeft, Link } from '@element-plus/icons-vue'
import { APP_LOGO } from '@/constants/brand'
import pkg from '../../package.json'

const { t } = useI18n()
const router = useRouter()
const isElectron = isElectronApp()
// 版本号取自 package.json，构建时注入；桌面版亦可由主进程覆盖但此处统一用前端版本
const version = computed(() => pkg.version || '—')
const goBack = () => router.push('/app/settings')

// 彩蛋：连续点击恋语图标 10 次跳转爱发电赞助页。
// 计数窗口 2s，中断则重置，避免误触。
const SPONSOR_URL = 'https://ifdian.net/a/Lianyuchat'
const logoClickCount = ref(0)
let logoClickTimer = null
function handleLogoClick() {
  logoClickCount.value += 1
  if (logoClickTimer) clearTimeout(logoClickTimer)
  logoClickTimer = setTimeout(() => { logoClickCount.value = 0 }, 2000)
  if (logoClickCount.value >= 10) {
    logoClickCount.value = 0
    if (logoClickTimer) { clearTimeout(logoClickTimer); logoClickTimer = null }
    // Electron 下经 setWindowOpenHandler 走 shell.openExternal（需 host 在白名单）；
    // Web 下浏览器原生 window.open 直接生效。
    window.open(SPONSOR_URL, '_blank', 'noopener,noreferrer')
  }
}
</script>

<style lang="scss" scoped>
.about-page {
  max-width: $narrow-page-max;
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

.about-card {
  border-radius: $radius-lg;
  padding: $space-6;
}

.about-brand {
  display: flex;
  align-items: center;
  gap: $space-4;
  margin-bottom: $space-5;
  padding-bottom: $space-5;
  border-bottom: 1px solid rgba(128, 128, 140, 0.15);
}

.about-logo {
  width: 56px;
  height: 56px;
  border-radius: $radius-lg;
  object-fit: contain;
  flex-shrink: 0;
  cursor: pointer;
  user-select: none;
  transition: transform 0.2s cubic-bezier(0.4, 0, 0.2, 1);
  &:hover { transform: scale(1.06); }
  &:active { transform: scale(0.96); }
}

.about-brand__name {
  font-size: $font-size-xl;
  font-weight: $font-weight-bold;
  color: $color-text-primary;
}

.about-brand__en {
  font-size: $font-size-sm;
  color: $color-text-muted;
  margin-left: $space-2;
  font-weight: $font-weight-normal;
}

.about-info {
  display: flex;
  flex-direction: column;
  gap: $space-3;
  margin-bottom: $space-5;
  padding-bottom: $space-5;
  border-bottom: 1px solid rgba(128, 128, 140, 0.15);
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

  &.mono {
    font-family: $font-mono;
    font-size: $font-size-xs;
    opacity: 0.75;
  }
}

.about-intro {
  font-size: $font-size-sm;
  color: $color-text-secondary;
  line-height: $line-height-normal;
  margin: 0;
}

.about-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: $space-4;
}

.about-copyright {
  font-size: $font-size-xs;
  color: $color-text-muted;
}

.about-devs {
  display: flex;
  flex-direction: column;
  gap: $space-3;
}

.dev-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: $space-3 $space-4;
  border-radius: $radius-md;
  background: rgba(128, 128, 140, 0.06);
  text-decoration: none;
  color: $color-text-primary;
  font-size: $font-size-base;
  font-weight: $font-weight-medium;
  transition: all $transition-fast;
  &:hover {
    background: rgba($color-pink-rgb, 0.08);
    color: $color-pink-primary;
  }
}

.dev-link-icon {
  font-size: $font-size-sm;
  color: $color-text-muted;
  .dev-item:hover & { color: $color-pink-primary; }
}

.dev-group {
  & + .dev-group {
    margin-top: $space-4;
  }
}

.dev-role {
  font-size: $font-size-xs;
  font-weight: $font-weight-semibold;
  color: $color-text-muted;
  text-transform: uppercase;
  letter-spacing: 0.5px;
  margin: 0 0 $space-2 0;
}

.dev-thanks {
  cursor: default;
  &:hover {
    background: rgba(128, 128, 140, 0.06);
    color: $color-text-primary;
  }
}
</style>
