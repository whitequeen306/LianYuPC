<template>
  <div
    class="cast-showcase"
    :class="`cast-showcase--cols-${columns}`"
    @mousemove="onPointerMove"
    @mouseleave="onPointerLeave"
  >
    <div class="cast-showcase__glow" :style="glowStyle" />

    <div class="cast-showcase__grid">
      <article
        v-for="(role, index) in roles"
        :key="role.id"
        class="cast-card"
        :class="[
          `cast-card--${role.id}`,
          {
            'is-active': activeId === role.id,
            'is-dimmed': activeId && activeId !== role.id,
          },
        ]"
        :style="{ '--i': index }"
        @mouseenter="activeId = role.id"
        @focusin="activeId = role.id"
        @click="activeId = role.id"
      >
        <div class="cast-card__inner">
          <header class="cast-card__head">
            <span class="cast-card__index">{{ formatIndex(index) }}</span>
            <span class="cast-card__name">{{ role.name }}</span>
          </header>

          <p class="cast-card__line">{{ role.line }}</p>

          <div class="cast-card__frame">
            <img
              :src="role.src"
              :alt="role.name"
              class="cast-card__img"
              loading="lazy"
              @error="role.imgError = true"
            >
            <div v-if="role.imgError" class="cast-card__fallback">{{ role.name }}</div>
          </div>
        </div>
      </article>
    </div>

    <p v-if="showHint" class="cast-showcase__hint">
      <span class="cast-showcase__hint-dot" />
      {{ hintText }}
    </p>
  </div>
</template>

<script setup>
import { computed, ref } from 'vue'

const props = defineProps({
  roles: {
    type: Array,
    required: true,
  },
  columns: {
    type: Number,
    default: 2,
    validator: (v) => [2, 3].includes(v),
  },
  showHint: {
    type: Boolean,
    default: true,
  },
  hintText: {
    type: String,
    default: '悬停或点击卡片，聆听 TA 对你说的话',
  },
})

const activeId = ref(null)
const pointer = ref({ x: 0.5, y: 0.5 })

const glowStyle = computed(() => ({
  '--px': `${pointer.value.x * 100}%`,
  '--py': `${pointer.value.y * 100}%`,
}))

function formatIndex(index) {
  return String(index + 1).padStart(2, '0')
}

function onPointerMove(e) {
  const rect = e.currentTarget.getBoundingClientRect()
  pointer.value = {
    x: (e.clientX - rect.left) / rect.width,
    y: (e.clientY - rect.top) / rect.height,
  }
}

function onPointerLeave() {
  pointer.value = { x: 0.5, y: 0.5 }
  activeId.value = null
}
</script>

<style lang="scss" scoped>
.cast-showcase {
  position: relative;
}

.cast-showcase__glow {
  position: absolute;
  inset: -20%;
  background: radial-gradient(
    circle at var(--px) var(--py),
    rgba(244, 166, 181, 0.22),
    transparent 42%
  );
  pointer-events: none;
  transition: opacity 0.4s ease;
  opacity: 0.85;
}

.cast-showcase__grid {
  position: relative;
  z-index: 1;
  display: grid;
  gap: clamp(12px, 1.4vw, 18px);
}

.cast-showcase--cols-2 .cast-showcase__grid {
  grid-template-columns: repeat(2, 1fr);
}

.cast-showcase--cols-3 .cast-showcase__grid {
  grid-template-columns: repeat(3, 1fr);
}

.cast-card {
  border: none;
  padding: 0;
  text-align: left;
  cursor: pointer;
  border-radius: 22px;
  background: transparent;
  transition:
    transform 0.45s cubic-bezier(0.22, 1, 0.36, 1),
    filter 0.45s ease,
    opacity 0.45s ease;
  animation: castIn 0.9s cubic-bezier(0.22, 1, 0.36, 1) both;
  animation-delay: calc(var(--i) * 0.08s);

  &.is-active {
    transform: translateY(-6px) scale(1.02);
    z-index: 2;
  }

  &.is-dimmed {
    opacity: 0.55;
    filter: saturate(0.75);
  }
}

.cast-card__inner {
  height: 100%;
  display: grid;
  grid-template-rows: auto auto 1fr;
  gap: 10px;
  padding: clamp(12px, 1.2vw, 16px);
  border-radius: 22px;
  border: 1px solid rgba(255, 255, 255, 0.1);
  background: rgba(8, 12, 22, 0.55);
  backdrop-filter: blur(16px);
  overflow: hidden;
  transition:
    border-color 0.35s ease,
    box-shadow 0.35s ease;
}

.cast-card.is-active .cast-card__inner {
  border-color: rgba(244, 166, 181, 0.45);
  box-shadow:
    0 24px 50px rgba(0, 0, 0, 0.45),
    0 0 0 1px rgba(244, 166, 181, 0.12);
}

.cast-card--kurumi .cast-card__inner {
  background: linear-gradient(155deg, rgba(72, 28, 48, 0.65), rgba(8, 12, 22, 0.7));
}

.cast-card--yuno .cast-card__inner {
  background: linear-gradient(155deg, rgba(98, 32, 48, 0.6), rgba(8, 12, 22, 0.7));
}

.cast-card--ganyu .cast-card__inner {
  background: linear-gradient(155deg, rgba(32, 58, 98, 0.58), rgba(8, 12, 22, 0.7));
}

.cast-card--zero-two .cast-card__inner {
  background: linear-gradient(155deg, rgba(108, 42, 62, 0.58), rgba(8, 12, 22, 0.7));
}

.cast-card--mahiru .cast-card__inner {
  background: linear-gradient(155deg, rgba(52, 68, 98, 0.55), rgba(8, 12, 22, 0.7));
}

.cast-card--megumi .cast-card__inner {
  background: linear-gradient(155deg, rgba(48, 52, 62, 0.58), rgba(8, 12, 22, 0.7));
}

.cast-card__head {
  display: flex;
  align-items: baseline;
  gap: 10px;
}

.cast-card__index {
  font-family: var(--landing-font-display, serif);
  font-size: 0.7rem;
  letter-spacing: 0.2em;
  color: rgba(244, 166, 181, 0.75);
}

.cast-card__name {
  font-size: 0.82rem;
  color: rgba(255, 255, 255, 0.55);
  letter-spacing: 0.08em;
}

.cast-card__line {
  margin: 0;
  font-size: clamp(0.78rem, 1.1vw, 0.9rem);
  line-height: 1.65;
  color: rgba(255, 255, 255, 0.92);
  min-height: 2.8em;
}

.cast-card__frame {
  position: relative;
  border-radius: 16px;
  overflow: hidden;
  aspect-ratio: 3 / 4;
  max-height: clamp(120px, 18vh, 200px);
  margin-top: auto;
}

.cast-showcase--cols-3 .cast-card__frame {
  max-height: clamp(110px, 16vh, 180px);
}

.cast-card__img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  object-position: center top;
  transform: scale(1.06);
  filter: blur(1.4px) saturate(0.88) brightness(0.9);
  transition:
    filter 0.5s ease,
    transform 0.5s ease;
}

.cast-card.is-active .cast-card__img {
  filter: blur(0) saturate(1) brightness(1);
  transform: scale(1.03);
}

.cast-card__fallback {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: $font-size-xs;
  color: rgba(255, 255, 255, 0.7);
}

.cast-card__frame::after {
  content: '';
  position: absolute;
  inset: 0;
  background: linear-gradient(180deg, transparent 50%, rgba(6, 10, 18, 0.55) 100%);
  pointer-events: none;
}

.cast-showcase__hint {
  position: relative;
  z-index: 1;
  margin: 18px 0 0;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
  font-size: 0.78rem;
  letter-spacing: 0.12em;
  color: rgba(255, 255, 255, 0.45);
}

.cast-showcase__hint-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: $color-pink-primary;
  animation: pulse 2s ease-in-out infinite;
}

@media (max-width: 900px) {
  .cast-showcase--cols-3 .cast-showcase__grid {
    grid-template-columns: repeat(2, 1fr);
  }
}

@media (max-width: 640px) {
  .cast-showcase__grid {
    gap: 8px;
  }

  .cast-card__frame {
    max-height: 100px;
  }
}

@keyframes castIn {
  from {
    opacity: 0;
    transform: translateY(20px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

@keyframes pulse {
  0%,
  100% {
    opacity: 0.4;
    transform: scale(0.9);
  }
  50% {
    opacity: 1;
    transform: scale(1.1);
  }
}
</style>
