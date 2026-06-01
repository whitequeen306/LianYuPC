<template>
  <div
    ref="orbitRoot"
    class="hero-orbit"
    role="group"
    :aria-label="ariaLabel"
    @mouseenter="hovering = true"
    @mouseleave="hovering = false"
  >
    <div class="hero-orbit__stage">
      <div class="hero-orbit__aura" aria-hidden="true" />

      <div class="hero-orbit__hub">
        <p class="hero-orbit__hub-text">在这里，她们只围着你转</p>
      </div>

      <div class="hero-orbit__carousel">
        <div class="hero-orbit__ring" aria-hidden="true" />

        <figure
          v-for="(role, index) in roles"
          :key="role.id"
          class="hero-orbit__slot"
          :class="`hero-orbit__slot--${role.id}`"
          :style="cardStyle(index)"
          tabindex="0"
        >
          <div class="hero-orbit__card-face">
            <p v-if="role.hoverLine || role.line" class="hero-orbit__bubble" role="tooltip">
              {{ role.hoverLine || role.line }}
            </p>
            <div class="hero-orbit__card">
              <div class="hero-orbit__card-photo">
                <img
                  :src="role.src"
                  :alt="role.shortName || role.name"
                  loading="eager"
                  @error="onImgError(role)"
                >
              </div>
              <figcaption class="hero-orbit__card-name">
                {{ role.shortName || role.name }}
              </figcaption>
            </div>
          </div>
        </figure>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { useHeroOrbitIntro } from '@/composables/useHeroOrbitIntro.js'

const orbitRoot = ref(null)
const carouselSpinning = ref(false)
const hovering = ref(false)

useHeroOrbitIntro(orbitRoot, {
  onSpinStart: () => {
    carouselSpinning.value = true
  },
})

defineProps({
  roles: {
    type: Array,
    required: true,
  },
  ariaLabel: {
    type: String,
    default: '六位角色围成一圈',
  },
})

function cardStyle(index) {
  const count = 6
  const angle = ((360 / count) * index - 90) * (Math.PI / 180)
  const cos = Math.cos(angle).toFixed(5)
  const sin = Math.sin(angle).toFixed(5)
  return {
    '--orbit-cos': cos,
    '--orbit-sin': sin,
    '--orbit-angle': `${(-360 / count) * index}deg`,
    '--float-delay': `${index * 0.5}s`,
  }
}

function onImgError(role) {
  role.imgError = true
}
</script>

<style lang="scss" scoped>
.hero-orbit {
  --orbit-r: clamp(138px, 18vw, 182px);
  --card-w: clamp(68px, 7.8vw, 86px);
  --card-h: calc(var(--card-w) * 4 / 3);
  width: 100%;
  display: flex;
  justify-content: center;
  overflow: visible;
  padding-top: 56px;
}

.hero-orbit__stage {
  position: relative;
  width: calc(var(--orbit-r) * 2 + var(--card-w));
  height: calc(var(--orbit-r) * 2 + var(--card-h));
  flex-shrink: 0;
  overflow: visible;
}

.hero-orbit__aura,
.hero-orbit__hub {
  position: absolute;
  left: 50%;
  top: 50%;
}

.hero-orbit__aura {
  width: calc(var(--orbit-r) * 2 + var(--card-w) * 0.4);
  height: calc(var(--orbit-r) * 2 + var(--card-w) * 0.4);
  margin-left: calc((var(--orbit-r) * 2 + var(--card-w) * 0.4) / -2);
  margin-top: calc((var(--orbit-r) * 2 + var(--card-w) * 0.4) / -2);
  border-radius: 50%;
  background: radial-gradient(circle, rgba(244, 166, 181, 0.16), transparent 70%);
  filter: blur(12px);
  pointer-events: none;
  animation: auraPulse 5s ease-in-out infinite;
}

.hero-orbit__hub {
  z-index: 12;
  transform: translate(-50%, -50%);
  width: min(11.5em, 52%);
  padding: 10px 8px;
  text-align: center;
  pointer-events: none;
  border-radius: 50%;
  background: radial-gradient(
    circle,
    rgba(6, 8, 15, 0.94) 0%,
    rgba(6, 8, 15, 0.7) 50%,
    transparent 75%
  );
}

.hero-orbit__hub-text {
  margin: 0;
  font-family: var(--landing-font-display, 'Noto Serif SC', serif);
  font-size: clamp(0.8rem, 1.15vw, 0.95rem);
  font-weight: 600;
  line-height: 1.6;
  color: #fff5f8;
  letter-spacing: 0.06em;
  text-shadow: 0 2px 12px rgba(0, 0, 0, 0.45);
}

.hero-orbit__carousel {
  position: absolute;
  inset: 0;
}

/* 旋转由 useHeroOrbitIntro 内 GSAP 控制；is-spinning 仅作状态标记 */
.hero-orbit__carousel.is-showcasing .hero-orbit__bubble {
  transition: none;
}

.hero-orbit__carousel.is-cycling-dialogue .hero-orbit__slot.is-orbit-spotlight {
  z-index: 18;
}

.hero-orbit__ring {
  position: absolute;
  left: 50%;
  top: 50%;
  width: calc(var(--orbit-r) * 2);
  height: calc(var(--orbit-r) * 2);
  margin-left: calc(var(--orbit-r) * -1);
  margin-top: calc(var(--orbit-r) * -1);
  border-radius: 50%;
  border: 1px dashed rgba(244, 166, 181, 0.28);
  box-sizing: border-box;
  pointer-events: none;
}

.hero-orbit__slot {
  position: absolute;
  left: 50%;
  top: 50%;
  display: block;
  width: var(--card-w);
  height: var(--card-h);
  margin: 0;
  margin-left: calc(var(--card-w) / -2);
  margin-top: calc(var(--card-h) / -2);
  z-index: 1;
  cursor: pointer;
  overflow: visible;
  transform: translate(
    var(--orbit-x, calc(var(--orbit-r) * var(--orbit-cos))),
    var(--orbit-y, calc(var(--orbit-r) * var(--orbit-sin)))
  );
  transform-origin: center center;
}

.hero-orbit__card-face {
  position: relative;
  width: 100%;
  height: 100%;
  transform: none;
}

.hero-orbit__card {
  width: 100%;
  height: 100%;
  border-radius: 14px;
  overflow: hidden;
  border: 1px solid rgba(255, 255, 255, 0.16);
  box-shadow: 0 12px 28px rgba(0, 0, 0, 0.42);
  background: rgba(8, 12, 22, 0.65);
  transform-origin: center center;
  transition:
    transform 0.35s ease,
    box-shadow 0.35s ease,
    border-color 0.35s ease;
}

.hero-orbit__bubble {
  position: absolute;
  left: 50%;
  bottom: calc(100% + 10px);
  z-index: 6;
  width: max-content;
  max-width: min(200px, 42vw);
  margin: 0;
  padding: 10px 12px;
  border-radius: 14px;
  border: 1px solid rgba(244, 166, 181, 0.35);
  background: rgba(10, 14, 26, 0.94);
  backdrop-filter: blur(10px);
  box-shadow: 0 12px 32px rgba(0, 0, 0, 0.45);
  font-size: 0.72rem;
  line-height: 1.55;
  color: rgba(255, 255, 255, 0.94);
  text-align: center;
  pointer-events: none;
  opacity: 0;
  transform: translateX(-50%) translateY(6px) scale(0.96);
  transition:
    opacity 0.28s ease,
    transform 0.32s cubic-bezier(0.22, 1, 0.36, 1);
}

.hero-orbit__bubble::after {
  content: '';
  position: absolute;
  left: 50%;
  bottom: -6px;
  width: 10px;
  height: 10px;
  margin-left: -5px;
  background: rgba(10, 14, 26, 0.94);
  border-right: 1px solid rgba(244, 166, 181, 0.25);
  border-bottom: 1px solid rgba(244, 166, 181, 0.25);
  transform: rotate(45deg);
}

.hero-orbit__carousel:not(.is-cycling-dialogue) .hero-orbit__slot:hover .hero-orbit__bubble,
.hero-orbit__carousel:not(.is-cycling-dialogue) .hero-orbit__slot:focus-within .hero-orbit__bubble {
  opacity: 1;
  transform: translateX(-50%) translateY(0) scale(1);
}

.hero-orbit__slot:hover,
.hero-orbit__slot:focus-within {
  z-index: 8;
}

.hero-orbit__carousel:not(.is-cycling-dialogue) .hero-orbit__slot:hover .hero-orbit__card,
.hero-orbit__carousel:not(.is-cycling-dialogue) .hero-orbit__slot:focus-within .hero-orbit__card {
  transform: scale(1.06);
  border-color: rgba(244, 166, 181, 0.45);
}

.hero-orbit__card-photo {
  width: 100%;
  height: calc(100% - 24px);
  overflow: hidden;
}

.hero-orbit__card-photo img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  object-position: center top;
  display: block;
  filter: blur(0.5px) saturate(0.92);
  transition: filter 0.3s ease;
}

.hero-orbit__slot:hover .hero-orbit__card-photo img {
  filter: none;
}

.hero-orbit__card-name {
  height: 24px;
  display: flex;
  align-items: center;
  justify-content: center;
  margin: 0;
  font-size: 0.62rem;
  letter-spacing: 0.05em;
  color: rgba(255, 255, 255, 0.88);
  background: rgba(6, 8, 15, 0.92);
}

@media (max-width: 768px) {
  .hero-orbit {
    --orbit-r: min(128px, 32vw);
    --card-w: 64px;
  }
}

@keyframes auraPulse {
  0%,
  100% {
    opacity: 0.6;
  }
  50% {
    opacity: 1;
  }
}
</style>
