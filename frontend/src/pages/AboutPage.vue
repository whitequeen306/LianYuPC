<template>
  <div class="about-page stagger-container">
    <header class="page-header">
      <h1 class="page-title">{{ t('about.title') }}</h1>
      <p class="page-desc">{{ t('about.desc') }}</p>
    </header>

    <!-- 应用信息 -->
    <section class="section stagger-item">
      <div class="glass about-card">
        <div class="about-brand">
          <div class="about-logo">恋</div>
          <div class="about-brand__text">
            <div class="about-brand__name">恋语 <span class="about-brand__en">LianYu</span></div>
          </div>
        </div>
        <div class="about-info">
          <div class="info-row">
            <span class="info-label">{{ t('about.version') }}</span>
            <span class="info-value mono">v{{ version }}</span>
          </div>
          <div class="info-row">
            <span class="info-label">{{ t('about.environment') }}</span>
            <span class="info-value">{{ isElectron ? t('about.envDesktop') : t('about.envWeb') }}</span>
          </div>
        </div>
        <p class="about-intro">{{ t('about.intro') }}</p>
      </div>
    </section>

    <!-- 技术栈 -->
    <section class="section stagger-item">
      <div class="section-header">
        <div>
          <h2 class="section-title">{{ t('about.techStack') }}</h2>
        </div>
      </div>
      <div class="glass about-card">
        <p class="about-intro">{{ t('about.techStackDesc') }}</p>
      </div>
    </section>

    <!-- 版权 -->
    <section class="section stagger-item">
      <div class="glass about-card about-footer">
        <span class="about-copyright">{{ t('about.copyright') }}</span>
        <el-button type="primary" class="btn-cta" :icon="ArrowLeft" @click="goBack">{{ t('about.backToSettings') }}</el-button>
      </div>
    </section>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { isElectronApp } from '@/utils/electron'
import { ArrowLeft } from '@element-plus/icons-vue'
import pkg from '../../package.json'

const { t } = useI18n()
const router = useRouter()
const isElectron = isElectronApp()
// 版本号取自 package.json，构建时注入；桌面版亦可由主进程覆盖但此处统一用前端版本
const version = computed(() => pkg.version || '—')
const goBack = () => router.push('/app/settings')
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
  border-bottom: 1px solid rgba(255, 255, 255, 0.06);
}

.about-logo {
  width: 56px;
  height: 56px;
  border-radius: $radius-lg;
  background: linear-gradient(135deg, rgba($color-pink-rgb, 0.85), rgba($color-pink-light, 0.7));
  color: #fff;
  font-size: 28px;
  font-weight: $font-weight-bold;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
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
  border-bottom: 1px solid rgba(255, 255, 255, 0.06);
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
</style>
