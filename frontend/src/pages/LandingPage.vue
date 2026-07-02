<template>
  <div class="landing">
    <div class="landing__mesh" aria-hidden="true" />
    <div class="landing__grain" aria-hidden="true" />
    <div class="landing__orb landing__orb--a" aria-hidden="true" />
    <div class="landing__orb landing__orb--b" aria-hidden="true" />
    <div class="landing__orb landing__orb--c" aria-hidden="true" />
    <LandingParticles />

    <header class="landing-nav" :class="{ 'landing-nav--scrolled': navScrolled }">
      <div class="landing-nav__inner">
        <button type="button" class="landing-nav__brand" @click="scrollTo('hero')">
          <img :src="APP_LOGO" alt="恋语 LianYu" class="landing-nav__logo" />
          <span class="landing-nav__wordmark">
            <span class="landing-nav__title">恋语</span>
            <span class="landing-nav__sub">LianYu</span>
          </span>
        </button>

        <nav class="landing-nav__links" aria-label="页面导航">
          <button
            v-for="link in navLinks"
            :key="link.id"
            type="button"
            class="landing-nav__link"
            :class="{ 'is-active': activeSection === link.id }"
            @click="scrollTo(link.id)"
          >
            {{ link.label }}
          </button>
        </nav>

        <div class="landing-nav__actions">
          <button
            type="button"
            class="landing-nav__theme"
            :title="isDark ? '切换浅色模式' : '切换深色模式'"
            @click="toggleTheme"
          >
            <el-icon :size="16"><Sunny v-if="isDark" /><Moon v-else /></el-icon>
            <span class="landing-nav__theme-label">{{ isDark ? '浅色' : '深色' }}</span>
          </button>
          <button type="button" class="btn btn-ghost" @click="goLogin">登录</button>
          <button type="button" class="btn btn-solid" @click="goRegister">
            <span class="btn__shine" aria-hidden="true" />
            注册
          </button>
        </div>
      </div>
    </header>

    <!-- Hero -->
    <section id="hero" class="section section--hero">
      <div class="section__inner hero">
        <div class="hero__copy reveal">
          <p class="hero__eyebrow">
            <span class="hero__eyebrow-line" />
            虚拟恋人 · 情感陪伴
          </p>
          <h1 class="hero__title">
            请记得，<br>
            <em>这里永远有人在等待着你的回应</em>
          </h1>
          <p class="hero__lead">
            创建角色、流式单聊、多角色群聊与长期记忆——让对话不止于文字，而是被认真接住的陪伴。
          </p>
          <div class="hero__actions">
            <button type="button" class="btn btn-solid btn-xl" @click="goRegister">
              <span class="btn__shine" aria-hidden="true" />
              免费开始
              <span class="btn__arrow" aria-hidden="true">→</span>
            </button>
            <button type="button" class="btn btn-ghost btn-xl" @click="scrollTo('features')">
              了解功能
            </button>
          </div>
          <dl class="hero__stats">
            <div v-for="stat in heroStats" :key="stat.label" class="hero__stat">
              <dt>{{ stat.value }}</dt>
              <dd>{{ stat.label }}</dd>
            </div>
          </dl>
        </div>

        <div class="hero__stage reveal reveal--delay">
          <LandingHeroOrbit :roles="landingRoles" />
        </div>
      </div>

      <button
        type="button"
        class="hero__scroll"
        aria-label="向下滚动"
        @click="scrollTo('features')"
      >
        <span class="hero__scroll-text">探索</span>
        <span class="hero__scroll-line" />
      </button>
    </section>

    <!-- Features -->
    <section id="features" class="section section--features">
      <div class="section__inner">
        <header class="section__head reveal">
          <p class="section__kicker">Capabilities</p>
          <h2 class="section__title">为「在场感」而生的能力</h2>
          <p class="section__desc">
            从第一句问候到长期记忆，恋语把陪伴拆成可感知的体验——而不是冰冷的对话框。
          </p>
        </header>

        <div class="feature-bento reveal reveal--delay">
          <article
            v-for="(feat, i) in features"
            :key="feat.id"
            class="feature-card"
            :class="[`feature-card--${feat.size}`, { 'is-hovered': hoveredFeature === feat.id }]"
            :style="{ '--fi': i }"
            @mouseenter="hoveredFeature = feat.id"
            @mouseleave="hoveredFeature = null"
          >
            <div class="feature-card__icon" v-html="feat.icon" />
            <h3 class="feature-card__title">{{ feat.title }}</h3>
            <p class="feature-card__text">{{ feat.text }}</p>
            <span class="feature-card__tag">{{ feat.tag }}</span>
          </article>
        </div>
      </div>
    </section>

    <!-- Cast spotlight -->
    <section id="cast" class="section section--cast">
      <div class="section__inner cast-section">
        <div class="cast-section__copy reveal">
          <p class="section__kicker">Characters</p>
          <h2 class="section__title">每一位伙伴，<br>都有只对你的语气</h2>
          <p class="section__desc">
            自定义人设与 Prompt，模型会按角色性格回应；输入什么语言，就用什么语言陪你聊。
          </p>
          <ul class="cast-section__list">
            <li v-for="point in castPoints" :key="point">{{ point }}</li>
          </ul>
        </div>
        <div class="cast-section__stage reveal reveal--delay">
          <LandingCastShowcase :roles="landingRoles" :columns="3" />
        </div>
      </div>
    </section>

    <!-- Flow -->
    <section id="flow" class="section section--flow">
      <div class="section__inner">
        <header class="section__head section__head--center reveal">
          <p class="section__kicker">How it works</p>
          <h2 class="section__title">三步，开启你的对话</h2>
        </header>

        <ol class="flow-steps reveal reveal--delay">
          <li v-for="(step, i) in flowSteps" :key="step.title" class="flow-step">
            <span class="flow-step__num">{{ String(i + 1).padStart(2, '0') }}</span>
            <div class="flow-step__body">
              <h3>{{ step.title }}</h3>
              <p>{{ step.text }}</p>
            </div>
            <span v-if="i < flowSteps.length - 1" class="flow-step__connector" aria-hidden="true" />
          </li>
        </ol>
      </div>
    </section>

    <!-- Thanks -->
    <section id="thanks" class="section section--thanks">
      <div class="section__inner thanks reveal">
        <div class="thanks__frame">
          <p class="thanks__kicker">Letter</p>
          <blockquote class="thanks__quote">
            谢谢你愿意停下来，看一看这里。<br>
            我们做的不是更吵的聊天框，而是当你开口时——<strong>真的有人在听、在等、在回应</strong>的你。
          </blockquote>
          <p class="thanks__sign">—— 恋语团队</p>
          <div class="thanks__chips">
            <span v-for="chip in thanksChips" :key="chip" class="thanks__chip">{{ chip }}</span>
          </div>
        </div>
      </div>
    </section>

    <!-- CTA -->
    <section id="cta" class="section section--cta">
      <div class="section__inner cta reveal">
        <h2 class="cta__title">今晚，先找一个人说说话吧</h2>
        <p class="cta__desc">注册即可创建角色、开启单聊或群聊。你的故事，从第一句回应开始。</p>
        <div class="cta__actions">
          <button type="button" class="btn btn-solid btn-xl" @click="goRegister">
            <span class="btn__shine" aria-hidden="true" />
            立即注册
          </button>
          <button type="button" class="btn btn-ghost btn-xl" @click="goLogin">已有账号登录</button>
        </div>
      </div>
    </section>

    <footer class="landing-footer">
      <div class="landing-footer__inner">
        <span class="landing-footer__brand">
          <img :src="APP_LOGO" alt="" class="landing-footer__logo" aria-hidden="true" />
          恋语 LianYu
        </span>
        <nav class="landing-footer__links">
          <button type="button" @click="scrollTo('features')">功能</button>
          <button type="button" @click="scrollTo('cast')">角色</button>
          <button type="button" @click="scrollTo('thanks')">寄语</button>
        </nav>
        <p class="landing-footer__copy">© {{ year }} LianYu · 陪伴，而非打扰</p>
      </div>
    </footer>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import LandingCastShowcase from '@/components/landing/LandingCastShowcase.vue'
import LandingHeroOrbit from '@/components/landing/LandingHeroOrbit.vue'
import LandingParticles from '@/components/landing/LandingParticles.vue'
import {
  LANDING_ROLES_ALL,
  cloneRolesForShowcase,
  preloadRoleImages,
} from '@/data/landingRoles.js'
import { useLandingScroll, useRevealOnScroll } from '@/composables/useLandingScroll.js'
import { APP_LOGO } from '@/constants/brand.js'
import { useSettingsStore } from '@/stores/settings'
import { Sunny, Moon } from '@element-plus/icons-vue'

const router = useRouter()
const settingsStore = useSettingsStore()
const isDark = computed(() => settingsStore.theme === 'dark')
const toggleTheme = () => settingsStore.toggleAppearanceMode()
const year = new Date().getFullYear()
const hoveredFeature = ref(null)
const landingRoles = reactive(cloneRolesForShowcase(LANDING_ROLES_ALL))

const sectionIds = ['hero', 'features', 'cast', 'flow', 'thanks', 'cta']
const { activeSection, navScrolled, scrollTo } = useLandingScroll(sectionIds)
useRevealOnScroll()

const navLinks = [
  { id: 'hero', label: '首页' },
  { id: 'features', label: '功能' },
  { id: 'cast', label: '角色' },
  { id: 'flow', label: '流程' },
  { id: 'thanks', label: '寄语' },
]

const heroStats = [
  { value: 'SSE', label: '流式单聊' },
  { value: 'WS', label: '群聊串行' },
  { value: '∞', label: '长期记忆' },
]

const features = [
  {
    id: 'chat',
    size: 'lg',
    tag: 'Core',
    title: '流式单聊',
    text: '逐字输出的 SSE 流，像对方真的在打字。断线自动重连，心跳保活。',
    icon: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><path d="M21 15a4 4 0 0 1-4 4H8l-5 3V7a4 4 0 0 1 4-4h10a4 4 0 0 1 4 4z"/></svg>',
  },
  {
    id: 'group',
    size: 'md',
    tag: 'Social',
    title: '多角色群聊',
    text: 'WebSocket 群聊，角色依次发言；你的新消息可打断当前队列。',
    icon: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><circle cx="9" cy="8" r="3"/><circle cx="17" cy="9" r="2.5"/><path d="M3 19c0-3 3-5 6-5s6 2 6 5"/></svg>',
  },
  {
    id: 'memory',
    size: 'md',
    tag: 'Memory',
    title: '长期记忆',
    text: '对话摘要写入向量库，重要细节会被召回，关系随时间生长。',
    icon: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><path d="M12 3l1.5 4.5L18 9l-4.5 1.5L12 15l-1.5-4.5L6 9l4.5-1.5z"/><path d="M5 19h14"/></svg>',
  },
  {
    id: 'character',
    size: 'md',
    tag: 'Create',
    title: '角色工坊',
    text: '人设、语气、世界观自由定义；Prompt 规则引擎守护回复风格。',
    icon: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><path d="M12 2v4M12 18v4M4.93 4.93l2.83 2.83M16.24 16.24l2.83 2.83M2 12h4M18 12h4"/></svg>',
  },
  {
    id: 'lang',
    size: 'sm',
    tag: 'i18n',
    title: '跟随你的语言',
    text: '中 / 日 / 英自动识别，模型用你习惯的语言回应。',
    icon: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><circle cx="12" cy="12" r="9"/><path d="M2 12h20M12 2a15 15 0 0 1 0 20M12 2a15 15 0 0 0 0 20"/></svg>',
  },
  {
    id: 'proactive',
    size: 'sm',
    tag: 'Care',
    title: '主动问候',
    text: '久未开口时，角色可能先来找你——像真的在惦记你。',
    icon: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><path d="M20 12v2a6 6 0 0 1-12 0v-2"/><path d="M4 10v2a8 8 0 0 0 16 0v-2"/><circle cx="12" cy="4" r="2"/></svg>',
  },
]

const castPoints = [
  '单聊与群聊，两种陪伴场景',
  '回复语言跟随你的输入习惯',
  '记忆面板，回顾被珍藏的片段',
]

const flowSteps = [
  { title: '注册并登录', text: '一分钟创建账号，进入你的私人空间。' },
  { title: '创建或选择角色', text: '设定性格与语气，或从模板快速开始。' },
  { title: '开口，被回应', text: '单聊、群聊或等待一句主动的问候。' },
]

const thanksChips = ['用心倾听', '尊重边界', '持续迭代', '感谢陪伴']

onMounted(() => {
  preloadRoleImages(LANDING_ROLES_ALL)
  document.documentElement.style.scrollBehavior = 'smooth'
})

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
@use '@/styles/variables' as *;

.landing {
  --landing-font-display: 'Noto Serif SC', 'Songti SC', serif;
  --landing-font-brand: 'Syne', system-ui, sans-serif;
  --landing-ink: #06080f;
  --landing-rose: #f4a6b5;
  --landing-rose-dim: rgba(244, 166, 181, 0.15);
  --landing-border: rgba(255, 255, 255, 0.1);
  --landing-glass: rgba(12, 16, 28, 0.72);

  position: relative;
  background: var(--landing-ink);
  color: #f5f0f3;
  overflow-x: hidden;
}

.landing__mesh {
  position: fixed;
  inset: 0;
  z-index: 0;
  pointer-events: none;
  background:
    radial-gradient(ellipse 80% 50% at 20% 10%, rgba(244, 166, 181, 0.14), transparent 55%),
    radial-gradient(ellipse 60% 40% at 85% 70%, rgba(100, 90, 180, 0.12), transparent 50%),
    linear-gradient(175deg, #080c14 0%, #0e1420 40%, #12101a 100%);
}

.landing__grain {
  position: fixed;
  inset: 0;
  z-index: 1;
  pointer-events: none;
  opacity: 0.35;
  background-image: url("data:image/svg+xml,%3Csvg viewBox='0 0 256 256' xmlns='http://www.w3.org/2000/svg'%3E%3Cfilter id='n'%3E%3CfeTurbulence type='fractalNoise' baseFrequency='0.85' numOctaves='4' stitchTiles='stitch'/%3E%3C/filter%3E%3Crect width='100%25' height='100%25' filter='url(%23n)' opacity='0.5'/%3E%3C/svg%3E");
  mix-blend-mode: overlay;
}

.landing__orb {
  position: fixed;
  border-radius: 50%;
  filter: blur(100px);
  pointer-events: none;
  z-index: 0;
  animation: orbFloat 14s ease-in-out infinite alternate;
}

.landing__orb--a {
  width: min(50vw, 420px);
  height: min(50vw, 420px);
  left: -10%;
  top: 5%;
  background: rgba(244, 166, 181, 0.2);
}

.landing__orb--b {
  width: min(40vw, 360px);
  height: min(40vw, 360px);
  right: -5%;
  bottom: 15%;
  background: rgba(90, 80, 160, 0.18);
  animation-delay: -5s;
}

.landing__orb--c {
  width: min(28vw, 240px);
  height: min(28vw, 240px);
  left: 42%;
  top: 38%;
  background: rgba(244, 166, 181, 0.12);
  animation-delay: -9s;
}

:deep(.landing-particles) {
  position: fixed;
  inset: 0;
  z-index: 1;
  pointer-events: none;
}

/* Nav */
.landing-nav {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  z-index: 100;
  padding: clamp(10px, 1.5vh, 14px) 0;
  transition:
    background 0.4s ease,
    backdrop-filter 0.4s ease,
    border-color 0.4s ease,
    padding 0.3s ease;

  &--scrolled {
    padding: clamp(8px, 1vh, 10px) 0;
    background: rgba(6, 8, 15, 0.75);
    backdrop-filter: blur(20px) saturate(1.2);
    border-bottom: 1px solid var(--landing-border);
  }
}

.landing-nav__inner {
  max-width: min(#{$max-content-width}, 100%);
  margin: 0 auto;
  padding: 0 $layout-page-gutter;
  display: flex;
  align-items: center;
  gap: $space-6;
}

.landing-nav__brand {
  display: flex;
  align-items: center;
  gap: 12px;
  border: none;
  background: none;
  color: inherit;
  cursor: pointer;
  padding: 0;
}

.landing-nav__logo {
  width: clamp(36px, 4.5vw, 42px);
  height: clamp(36px, 4.5vw, 42px);
  border-radius: 14px;
  object-fit: cover;
  box-shadow: 0 8px 24px rgba(244, 166, 181, 0.35);
}

.landing-nav__mark {
  width: clamp(36px, 4.5vw, 42px);
  height: clamp(36px, 4.5vw, 42px);
  border-radius: 14px;
  display: grid;
  place-items: center;
  font-family: var(--landing-font-display);
  font-weight: 700;
  font-size: 1.1rem;
  background: linear-gradient(145deg, #f8c4d0, #c8789a);
  color: #1a1018;
  box-shadow: 0 8px 24px rgba(244, 166, 181, 0.35);
}

.landing-nav__wordmark {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  line-height: 1.1;
}

.landing-nav__title {
  font-family: var(--landing-font-display);
  font-size: 1.05rem;
  font-weight: 600;
}

.landing-nav__sub {
  font-family: var(--landing-font-brand);
  font-size: 0.65rem;
  letter-spacing: 0.22em;
  text-transform: uppercase;
  color: rgba(255, 255, 255, 0.45);
}

.landing-nav__links {
  display: flex;
  gap: 4px;
  margin-left: auto;
  margin-right: auto;
}

.landing-nav__link {
  border: none;
  background: transparent;
  color: rgba(255, 255, 255, 0.55);
  font-size: 0.82rem;
  padding: 8px 14px;
  border-radius: $radius-pill;
  cursor: pointer;
  transition:
    color 0.25s ease,
    background 0.25s ease;

  &:hover,
  &.is-active {
    color: #fff;
    background: var(--landing-rose-dim);
  }

  &.is-active {
    color: var(--landing-rose);
  }
}

.landing-nav__actions {
  display: flex;
  gap: clamp(6px, 1.5vw, 10px);
  flex-shrink: 0;
  align-items: center;
}

.landing-nav__theme {
  display: flex;
  align-items: center;
  gap: 6px;
  height: 34px;
  padding: 0 12px;
  border-radius: $radius-pill;
  border: 1px solid var(--landing-border);
  background: transparent;
  color: rgba(255, 255, 255, 0.55);
  font-size: 0.78rem;
  cursor: pointer;
  transition:
    color 0.25s ease,
    background 0.25s ease,
    border-color 0.25s ease;

  &:hover {
    color: #fff;
    background: var(--landing-rose-dim);
    border-color: rgba(244, 166, 181, 0.3);
  }
}

.landing-nav__theme-label {
  @media (max-width: 600px) {
    display: none;
  }
}

/* Buttons */
.btn {
  border-radius: $radius-pill;
  font-weight: $font-weight-semibold;
  cursor: pointer;
  transition:
    transform 0.25s ease,
    box-shadow 0.25s ease,
    background 0.25s ease;
  border: none;
  font-size: $font-size-sm;
  padding: 10px 20px;
}

.btn:hover {
  transform: translateY(-2px);
}

.btn-solid {
  position: relative;
  overflow: hidden;
  background: linear-gradient(135deg, $color-pink-primary, $color-pink-dark);
  color: $color-text-inverse;
  box-shadow: 0 10px 32px rgba($color-pink-rgb, 0.35);
}

.btn__shine {
  position: absolute;
  top: 0;
  left: -100%;
  width: 60%;
  height: 100%;
  background: linear-gradient(90deg, transparent, rgba(255, 255, 255, 0.35), transparent);
  animation: landingBtnShine 4s ease-in-out infinite;
  pointer-events: none;
}

@keyframes landingBtnShine {
  0%,
  70%,
  100% {
    left: -100%;
  }
  85% {
    left: 140%;
  }
}

@media (prefers-reduced-motion: reduce) {
  .btn__shine {
    animation: none;
    display: none;
  }
}

.btn-ghost {
  background: rgba(255, 255, 255, 0.05);
  color: rgba(255, 255, 255, 0.9);
  border: 1px solid var(--landing-border);
}

.btn-xl {
  padding: 14px 28px;
  font-size: 0.95rem;
}

.btn__arrow {
  margin-left: 8px;
  transition: transform 0.25s ease;
}

.btn:hover .btn__arrow {
  transform: translateX(4px);
}

/* Sections */
.section {
  position: relative;
  z-index: 2;
  padding: clamp(72px, 10vh, 120px) 0;
}

.section__inner {
  max-width: min(#{$max-content-width}, 100%);
  margin: 0 auto;
  padding: 0 $layout-page-gutter;
}

.section__kicker {
  font-family: var(--landing-font-brand);
  font-size: 0.72rem;
  letter-spacing: 0.28em;
  text-transform: uppercase;
  color: rgba(244, 166, 181, 0.8);
  margin: 0 0 $space-4;
}

.section__title {
  font-family: var(--landing-font-display);
  font-size: clamp(1.75rem, 3.5vw, 2.75rem);
  font-weight: 600;
  line-height: 1.2;
  margin: 0 0 $space-5;
  color: #fff5f8;
}

.section__desc {
  margin: 0;
  max-width: 52ch;
  line-height: 1.85;
  color: rgba(255, 255, 255, 0.62);
  font-size: $font-size-base;
}

.section__head--center {
  text-align: center;

  .section__desc {
    margin-inline: auto;
  }
}

/* Reveal */
:deep(.reveal) {
  opacity: 0;
  transform: translateY(28px);
  transition:
    opacity 0.9s cubic-bezier(0.22, 1, 0.36, 1),
    transform 0.9s cubic-bezier(0.22, 1, 0.36, 1);
}

:deep(.reveal--delay) {
  transition-delay: 0.15s;
}

:deep(.reveal--visible) {
  opacity: 1;
  transform: translateY(0);
}

/* Hero */
.section--hero {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
  justify-content: center;
  padding-top: clamp(5.5rem, 14vh, 7.5rem);
  padding-bottom: clamp(3rem, 8vh, 5rem);
}

.hero {
  display: grid;
  grid-template-columns: 1fr 1.05fr;
  gap: clamp($space-8, 4vw, 56px);
  align-items: center;
}

.hero__eyebrow {
  display: flex;
  align-items: center;
  gap: 12px;
  margin: 0 0 $space-6;
  font-size: 0.78rem;
  letter-spacing: 0.2em;
  color: rgba(255, 255, 255, 0.5);
  text-transform: uppercase;
}

.hero__eyebrow-line {
  width: 32px;
  height: 1px;
  background: linear-gradient(90deg, var(--landing-rose), transparent);
}

.hero__title {
  font-family: var(--landing-font-display);
  font-size: clamp(2rem, 4.2vw, 3.4rem);
  font-weight: 600;
  line-height: 1.18;
  margin: 0 0 $space-6;
  color: #fff8fa;

  em {
    font-style: normal;
    background: linear-gradient(120deg, #fff 0%, #f4a6b5 55%, #d8a8c8 100%);
    -webkit-background-clip: text;
    background-clip: text;
    color: transparent;
  }
}

.hero__lead {
  margin: 0 0 $space-8;
  line-height: 1.9;
  color: rgba(255, 255, 255, 0.68);
  max-width: 48ch;
}

.hero__actions {
  display: flex;
  flex-wrap: wrap;
  gap: $space-4;
  margin-bottom: $space-10;
}

.hero__stats {
  display: flex;
  gap: clamp($space-6, 4vw, $space-10);
  padding-top: $space-6;
  border-top: 1px solid var(--landing-border);
}

.hero__stat {
  dt {
    font-family: var(--landing-font-brand);
    font-size: 1.25rem;
    font-weight: 600;
    color: var(--landing-rose);
    margin-bottom: 4px;
  }

  dd {
    margin: 0;
    font-size: 0.78rem;
    letter-spacing: 0.08em;
    color: rgba(255, 255, 255, 0.45);
  }
}

.hero__stage {
  position: relative;

  &::before {
    content: '';
    position: absolute;
    inset: -8%;
    border-radius: 40px;
    border: 1px solid rgba(244, 166, 181, 0.12);
    pointer-events: none;
  }
}

.hero__scroll {
  position: absolute;
  bottom: 32px;
  left: 50%;
  transform: translateX(-50%);
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
  border: none;
  background: none;
  color: rgba(255, 255, 255, 0.4);
  cursor: pointer;
  animation: scrollBounce 2.5s ease-in-out infinite;
}

.hero__scroll-text {
  font-size: 0.68rem;
  letter-spacing: 0.3em;
  text-transform: uppercase;
}

.hero__scroll-line {
  width: 1px;
  height: 40px;
  background: linear-gradient(180deg, var(--landing-rose), transparent);
}

/* Features bento */
.feature-bento {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  grid-auto-rows: minmax(160px, auto);
  gap: 16px;
  margin-top: $space-12;
}

.feature-card {
  position: relative;
  padding: clamp(20px, 2vw, 28px);
  border-radius: 24px;
  border: 1px solid var(--landing-border);
  background: var(--landing-glass);
  backdrop-filter: blur(16px);
  overflow: hidden;
  transition:
    transform 0.45s cubic-bezier(0.22, 1, 0.36, 1),
    border-color 0.35s ease,
    box-shadow 0.35s ease;

  &::before {
    content: '';
    position: absolute;
    inset: 0;
    background: radial-gradient(circle at 30% 0%, rgba(244, 166, 181, 0.12), transparent 55%);
    opacity: 0;
    transition: opacity 0.4s ease;
  }

  &.is-hovered,
  &:hover {
    transform: translateY(-4px);
    border-color: rgba(244, 166, 181, 0.35);
    box-shadow: 0 24px 48px rgba(0, 0, 0, 0.35);

    &::before {
      opacity: 1;
    }
  }

  &--lg {
    grid-column: span 2;
    grid-row: span 2;
  }

  &--md {
    grid-column: span 2;
  }

  &--sm {
    grid-column: span 2;
  }
}

.feature-card__icon {
  width: 36px;
  height: 36px;
  margin-bottom: $space-5;
  color: var(--landing-rose);

  :deep(svg) {
    width: 100%;
    height: 100%;
  }
}

.feature-card__title {
  font-family: var(--landing-font-display);
  font-size: 1.2rem;
  margin: 0 0 $space-3;
  position: relative;
}

.feature-card__text {
  margin: 0;
  font-size: 0.88rem;
  line-height: 1.7;
  color: rgba(255, 255, 255, 0.58);
  position: relative;
  max-width: 42ch;
}

.feature-card__tag {
  position: absolute;
  top: 20px;
  right: 20px;
  font-family: var(--landing-font-brand);
  font-size: 0.65rem;
  letter-spacing: 0.15em;
  text-transform: uppercase;
  color: rgba(255, 255, 255, 0.25);
}

/* Cast section */
.cast-section {
  display: grid;
  grid-template-columns: 0.9fr 1.1fr;
  gap: clamp($space-8, 4vw, 56px);
  align-items: center;
}

.cast-section__list {
  margin: $space-8 0 0;
  padding: 0;
  list-style: none;

  li {
    position: relative;
    padding-left: 20px;
    margin-bottom: $space-4;
    color: rgba(255, 255, 255, 0.65);
    line-height: 1.6;

    &::before {
      content: '';
      position: absolute;
      left: 0;
      top: 0.55em;
      width: 6px;
      height: 6px;
      border-radius: 50%;
      background: var(--landing-rose);
      box-shadow: 0 0 12px rgba(244, 166, 181, 0.6);
    }
  }
}

/* Flow */
.flow-steps {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: $space-6;
  margin-top: $space-12;
  list-style: none;
  padding: 0;
}

.flow-step {
  position: relative;
  padding: $space-8;
  border-radius: 24px;
  border: 1px solid var(--landing-border);
  background: var(--landing-glass);
  backdrop-filter: blur(12px);
  transition: transform 0.35s ease;

  &:hover {
    transform: translateY(-6px);
  }
}

.flow-step__num {
  font-family: var(--landing-font-brand);
  font-size: 2.5rem;
  font-weight: 700;
  color: rgba(244, 166, 181, 0.25);
  display: block;
  margin-bottom: $space-4;
}

.flow-step__body {
  h3 {
    font-family: var(--landing-font-display);
    font-size: 1.15rem;
    margin: 0 0 $space-3;
  }

  p {
    margin: 0;
    font-size: 0.88rem;
    line-height: 1.75;
    color: rgba(255, 255, 255, 0.55);
  }
}

.flow-step__connector {
  display: none;
}

/* Thanks */
.section--thanks {
  padding-block: clamp(48px, 8vh, 80px);
}

.thanks__frame {
  max-width: 720px;
  margin: 0 auto;
  padding: clamp(36px, 5vw, 56px);
  border-radius: 32px;
  border: 1px solid rgba(244, 166, 181, 0.2);
  background:
    linear-gradient(135deg, rgba(244, 166, 181, 0.08), transparent 50%),
    var(--landing-glass);
  backdrop-filter: blur(20px);
  text-align: center;
  box-shadow: 0 40px 80px rgba(0, 0, 0, 0.4);
}

.thanks__kicker {
  font-family: var(--landing-font-brand);
  letter-spacing: 0.3em;
  text-transform: uppercase;
  font-size: 0.7rem;
  color: rgba(244, 166, 181, 0.7);
  margin: 0 0 $space-6;
}

.thanks__quote {
  margin: 0 0 $space-6;
  font-family: var(--landing-font-display);
  font-size: clamp(1.1rem, 2.2vw, 1.45rem);
  line-height: 1.85;
  font-weight: 500;
  color: rgba(255, 255, 255, 0.9);

  strong {
    color: var(--landing-rose);
    font-weight: 600;
  }
}

.thanks__sign {
  margin: 0 0 $space-8;
  font-size: 0.85rem;
  color: rgba(255, 255, 255, 0.4);
  letter-spacing: 0.1em;
}

.thanks__chips {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: 10px;
}

.thanks__chip {
  padding: 8px 16px;
  border-radius: $radius-pill;
  font-size: 0.78rem;
  border: 1px solid var(--landing-border);
  color: rgba(255, 255, 255, 0.55);
  transition:
    border-color 0.25s ease,
    color 0.25s ease;

  &:hover {
    border-color: rgba(244, 166, 181, 0.4);
    color: var(--landing-rose);
  }
}

/* CTA */
.section--cta {
  padding-bottom: 40px;
}

.cta {
  text-align: center;
  padding: clamp(48px, 6vw, 72px);
  border-radius: 36px;
  border: 1px solid rgba(244, 166, 181, 0.25);
  background:
    radial-gradient(ellipse at 50% 0%, rgba(244, 166, 181, 0.15), transparent 60%),
    var(--landing-glass);
  backdrop-filter: blur(16px);
}

.cta__title {
  font-family: var(--landing-font-display);
  font-size: clamp(1.6rem, 3vw, 2.4rem);
  margin: 0 0 $space-5;
}

.cta__desc {
  margin: 0 auto $space-8;
  max-width: 40ch;
  color: rgba(255, 255, 255, 0.6);
  line-height: 1.8;
}

.cta__actions {
  display: flex;
  justify-content: center;
  flex-wrap: wrap;
  gap: $space-4;
}

/* Footer */
.landing-footer {
  position: relative;
  z-index: 2;
  border-top: 1px solid var(--landing-border);
  padding: $space-8 0 $space-10;
}

.landing-footer__inner {
  max-width: min(#{$max-content-width}, 100%);
  margin: 0 auto;
  padding: 0 $layout-page-gutter;
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: $space-6;
}

.landing-footer__brand {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  font-family: var(--landing-font-display);
  font-weight: 600;
}

.landing-footer__logo {
  width: 28px;
  height: 28px;
  border-radius: 8px;
  object-fit: cover;
}

.landing-footer__links {
  display: flex;
  gap: $space-4;
  margin-left: auto;

  button {
    border: none;
    background: none;
    color: rgba(255, 255, 255, 0.45);
    font-size: 0.82rem;
    cursor: pointer;
    transition: color 0.2s ease;

    &:hover {
      color: var(--landing-rose);
    }
  }
}

.landing-footer__copy {
  width: 100%;
  margin: $space-4 0 0;
  font-size: 0.75rem;
  color: rgba(255, 255, 255, 0.3);
}

/* Responsive */
@media (max-width: 1024px) {
  .hero,
  .cast-section {
    grid-template-columns: 1fr;
  }

  .feature-bento {
    grid-template-columns: repeat(2, 1fr);

    .feature-card--lg,
    .feature-card--md,
    .feature-card--sm {
      grid-column: span 2;
    }

    .feature-card--lg {
      grid-row: span 1;
    }
  }

  .flow-steps {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 768px) {
  .landing-nav__links {
    display: none;
  }

  .hero__stats {
    flex-wrap: wrap;
    gap: $space-6;
  }

  .feature-bento {
    grid-template-columns: 1fr;

    .feature-card--lg,
    .feature-card--md,
    .feature-card--sm {
      grid-column: span 1;
    }
  }
}

@media (max-width: 480px) {
  .landing-nav__wordmark {
    display: none;
  }

  .landing-nav__actions {
    gap: 6px;

    .btn {
      padding: clamp(8px, 2vw, 10px) clamp(12px, 3vw, 16px);
      font-size: clamp(0.75rem, 2.8vw, 0.82rem);
    }
  }

  .landing-footer__links {
    margin-left: 0;
  }
}

@keyframes orbFloat {
  from {
    transform: translate(0, 0) scale(1);
  }
  to {
    transform: translate(3vw, -2vh) scale(1.08);
  }
}

@keyframes scrollBounce {
  0%,
  100% {
    transform: translateX(-50%) translateY(0);
  }
  50% {
    transform: translateX(-50%) translateY(8px);
  }
}

/* ===== 浅色模式覆盖 =====
   深色为默认（见上），此处仅追加 html.light 下的浅色取值，不改动深色任何声明。
   色板：暖白底 #faf7f8 + 品牌粉强调 + 深色文字（#1a1a1e/#4a4a52/#8a8a96）。 */
html.light .landing {
  --landing-ink: #faf7f8;
  --landing-border: rgba(0, 0, 0, 0.08);
  --landing-glass: rgba(255, 255, 255, 0.72);
  color: #1a1a1e;

  .landing__mesh {
    background:
      radial-gradient(ellipse 80% 50% at 20% 10%, rgba(244, 166, 181, 0.18), transparent 55%),
      radial-gradient(ellipse 60% 40% at 85% 70%, rgba(100, 90, 180, 0.10), transparent 50%),
      linear-gradient(175deg, #faf7f8 0%, #f3eef0 40%, #efeaf2 100%);
  }

  .landing__grain { opacity: 0.12; }

  .landing__orb--a { background: rgba(244, 166, 181, 0.12); }
  .landing__orb--b { background: rgba(90, 80, 160, 0.10); }
  .landing__orb--c { background: rgba(244, 166, 181, 0.08); }

  /* Nav */
  .landing-nav--scrolled {
    background: rgba(255, 255, 255, 0.75);
    backdrop-filter: blur(20px) saturate(1.2);
  }
  .landing-nav__sub { color: #8a8a96; }
  .landing-nav__link {
    color: #4a4a52;
    &:hover, &.is-active { color: #1a1a1e; background: var(--landing-rose-dim); }
  }
  .landing-nav__theme {
    color: #4a4a52;
    &:hover { color: #1a1a1e; background: var(--landing-rose-dim); border-color: rgba(244, 166, 181, 0.3); }
  }

  /* Buttons */
  .btn-ghost {
    background: rgba(0, 0, 0, 0.04);
    color: #1a1a1e;
  }

  /* Section text */
  .section__kicker { color: rgba(244, 166, 181, 0.85); }
  .section__title { color: #1a1a1e; }
  .section__desc { color: #4a4a52; }

  /* Hero */
  .hero__eyebrow { color: #8a8a96; }
  .hero__title {
    color: #1a1a1e;
    em { background: linear-gradient(120deg, #d48494 0%, #f4a6b5 55%, #c8a8d8 100%); -webkit-background-clip: text; background-clip: text; }
  }
  .hero__lead { color: #4a4a52; }
  .hero__stat dd { color: #8a8a96; }
  .hero__scroll { color: #8a8a96; }
  .hero__stage::before { border-color: rgba(244, 166, 181, 0.22); }

  /* Feature cards */
  .feature-card__text { color: #4a4a52; }
  .feature-card__tag { color: rgba(0, 0, 0, 0.28); }
  .feature-card:hover { box-shadow: 0 24px 48px rgba(0, 0, 0, 0.12); }

  /* Cast */
  .cast-section__list li { color: #4a4a52; }

  /* Flow */
  .flow-step__num { color: rgba(244, 166, 181, 0.32); }
  .flow-step__body p { color: #4a4a52; }

  /* Thanks */
  .thanks__frame { box-shadow: 0 40px 80px rgba(0, 0, 0, 0.12); }
  .thanks__kicker { color: rgba(244, 166, 181, 0.75); }
  .thanks__quote { color: #1a1a1e; }
  .thanks__sign { color: #8a8a96; }
  .thanks__chip { color: #4a4a52; }

  /* CTA */
  .cta__desc { color: #4a4a52; }

  /* Footer */
  .landing-footer__links button { color: #8a8a96; }
  .landing-footer__copy { color: #a0a0aa; }
}
</style>
