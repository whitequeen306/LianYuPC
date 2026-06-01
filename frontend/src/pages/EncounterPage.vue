<template>
  <div class="encounter">
    <div class="encounter__mesh" aria-hidden="true" />
    <div class="encounter__grain" aria-hidden="true" />

    <header class="encounter-nav">
      <button type="button" class="encounter-nav__back" @click="goHome">
        <span aria-hidden="true">←</span>
        返回首页
      </button>
      <span class="encounter-nav__brand">恋语 · 邂逅</span>
      <div class="encounter-nav__actions">
        <button type="button" class="btn btn-ghost" @click="goLogin">登录</button>
        <button type="button" class="btn btn-solid" @click="goRegister">注册</button>
      </div>
    </header>

    <main class="encounter-main">
      <header class="encounter-head">
        <p class="encounter-head__kicker">Encounter</p>
        <h1 class="encounter-head__title">一场邂逅</h1>
        <p class="encounter-head__desc">
          四位在等你开口的人。悬停聆听问候，选好心情后，注册即可把对话继续下去。
        </p>
      </header>

      <LandingCastShowcase
        :roles="encounterRoles"
        :columns="2"
        hint-text="选择让你想回复的那一位"
      />

      <div class="encounter-cta">
        <button type="button" class="btn btn-solid btn-xl" @click="goRegister">
          注册，继续这场对话
          <span class="btn__arrow" aria-hidden="true">→</span>
        </button>
        <button type="button" class="btn btn-ghost btn-xl" @click="goLogin">已有账号</button>
      </div>
    </main>
  </div>
</template>

<script setup>
import { onMounted, reactive } from 'vue'
import { useRouter } from 'vue-router'
import LandingCastShowcase from '@/components/landing/LandingCastShowcase.vue'
import {
  ENCOUNTER_ROLES,
  cloneRolesForShowcase,
  preloadRoleImages,
} from '@/data/landingRoles.js'

const router = useRouter()
const encounterRoles = reactive(cloneRolesForShowcase(ENCOUNTER_ROLES))

onMounted(() => {
  preloadRoleImages(ENCOUNTER_ROLES)
  document.documentElement.style.scrollBehavior = 'smooth'
})

function goHome() {
  router.push('/')
}

function goLogin() {
  router.push('/login')
}

function goRegister() {
  router.push('/register')
}
</script>

<style lang="scss">
@import url('https://fonts.googleapis.com/css2?family=Noto+Serif+SC:wght@500;600;700&family=Syne:wght@500;600;700&display=swap');
</style>

<style lang="scss" scoped>
.encounter {
  --landing-font-display: 'Noto Serif SC', 'Songti SC', serif;
  --landing-font-brand: 'Syne', system-ui, sans-serif;
  min-height: 100vh;
  position: relative;
  background: #06080f;
  color: #f5f0f3;
  padding: 24px clamp(16px, 4vw, 40px) 64px;
}

.encounter__mesh {
  position: fixed;
  inset: 0;
  pointer-events: none;
  background:
    radial-gradient(ellipse 70% 50% at 50% 0%, rgba(244, 166, 181, 0.16), transparent 55%),
    linear-gradient(180deg, #080c14, #0e1018 50%, #12101a);
}

.encounter__grain {
  position: fixed;
  inset: 0;
  pointer-events: none;
  opacity: 0.3;
  background-image: url("data:image/svg+xml,%3Csvg viewBox='0 0 256 256' xmlns='http://www.w3.org/2000/svg'%3E%3Cfilter id='n'%3E%3CfeTurbulence type='fractalNoise' baseFrequency='0.85' numOctaves='4' stitchTiles='stitch'/%3E%3C/filter%3E%3Crect width='100%25' height='100%25' filter='url(%23n)' opacity='0.5'/%3E%3C/svg%3E");
  mix-blend-mode: overlay;
}

.encounter-nav {
  position: relative;
  z-index: 2;
  max-width: 900px;
  margin: 0 auto 48px;
  display: flex;
  align-items: center;
  gap: $space-4;
}

.encounter-nav__back {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 8px 14px;
  border-radius: $radius-pill;
  border: 1px solid rgba(255, 255, 255, 0.12);
  background: rgba(255, 255, 255, 0.04);
  color: rgba(255, 255, 255, 0.75);
  font-size: 0.82rem;
  cursor: pointer;
  transition: background 0.25s ease, color 0.25s ease;

  &:hover {
    background: rgba(244, 166, 181, 0.12);
    color: #fff;
  }
}

.encounter-nav__brand {
  font-family: var(--landing-font-display);
  font-size: 0.9rem;
  letter-spacing: 0.1em;
  color: rgba(255, 255, 255, 0.5);
}

.encounter-nav__actions {
  margin-left: auto;
  display: flex;
  gap: 10px;
}

.encounter-main {
  position: relative;
  z-index: 2;
  max-width: 900px;
  margin: 0 auto;
}

.encounter-head {
  text-align: center;
  margin-bottom: clamp(32px, 5vh, 48px);
}

.encounter-head__kicker {
  font-family: var(--landing-font-brand);
  font-size: 0.72rem;
  letter-spacing: 0.28em;
  text-transform: uppercase;
  color: rgba(244, 166, 181, 0.75);
  margin: 0 0 $space-4;
}

.encounter-head__title {
  font-family: var(--landing-font-display);
  font-size: clamp(2rem, 5vw, 3rem);
  font-weight: 600;
  margin: 0 0 $space-5;
  background: linear-gradient(120deg, #fff, #f4a6b5);
  -webkit-background-clip: text;
  background-clip: text;
  color: transparent;
}

.encounter-head__desc {
  margin: 0 auto;
  max-width: 42ch;
  line-height: 1.85;
  color: rgba(255, 255, 255, 0.58);
  font-size: $font-size-base;
}

.encounter-cta {
  margin-top: $space-10;
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: $space-4;
}

.btn {
  border-radius: $radius-pill;
  font-weight: $font-weight-semibold;
  cursor: pointer;
  border: none;
  font-size: $font-size-sm;
  padding: 10px 20px;
  transition: transform 0.25s ease, box-shadow 0.25s ease;
}

.btn:hover {
  transform: translateY(-2px);
}

.btn-solid {
  background: linear-gradient(135deg, $color-pink-primary, $color-pink-dark);
  color: $color-text-inverse;
  box-shadow: 0 10px 32px rgba($color-pink-rgb, 0.35);
}

.btn-ghost {
  background: rgba(255, 255, 255, 0.05);
  color: rgba(255, 255, 255, 0.9);
  border: 1px solid rgba(255, 255, 255, 0.14);
}

.btn-xl {
  padding: 14px 28px;
  font-size: 0.95rem;
}

.btn__arrow {
  margin-left: 8px;
}

@media (max-width: 640px) {
  .encounter-nav__brand {
    display: none;
  }
}
</style>
