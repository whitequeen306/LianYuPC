<template>
  <div class="encounter-cards">
    <article
      v-for="(role, index) in roles"
      :key="role.id"
      class="encounter-card"
      :class="[`encounter-card--${role.id}`, { 'is-active': activeId === role.id }]"
      :style="{ '--i': index }"
      @mouseenter="activeId = role.id"
      @focusin="activeId = role.id"
      @mouseleave="activeId = null"
    >
      <div class="encounter-card__avatar">
        <img :src="role.src" :alt="role.name" loading="lazy" @error="role.imgError = true">
        <div v-if="role.imgError" class="encounter-card__fallback">{{ role.name }}</div>
      </div>
      <div class="encounter-card__body">
        <h2 class="encounter-card__name">{{ role.name }}</h2>
        <p class="encounter-card__line">{{ role.line }}</p>
      </div>
    </article>
  </div>
</template>

<script setup>
import { ref } from 'vue'

defineProps({
  roles: {
    type: Array,
    required: true,
  },
})

const activeId = ref(null)
</script>

<style lang="scss" scoped>
.encounter-cards {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: clamp(14px, 2vw, 20px);
}

.encounter-card {
  display: flex;
  gap: 14px;
  align-items: flex-start;
  padding: clamp(16px, 2vw, 22px);
  border-radius: 22px;
  border: 1px solid rgba(255, 255, 255, 0.1);
  background: rgba(10, 14, 26, 0.65);
  backdrop-filter: blur(14px);
  cursor: default;
  transition:
    transform 0.4s cubic-bezier(0.22, 1, 0.36, 1),
    border-color 0.35s ease,
    box-shadow 0.35s ease;
  animation: cardIn 0.75s cubic-bezier(0.22, 1, 0.36, 1) both;
  animation-delay: calc(var(--i) * 0.1s);

  &.is-active {
    transform: translateY(-4px);
    border-color: rgba(244, 166, 181, 0.4);
    box-shadow: 0 20px 44px rgba(0, 0, 0, 0.38);
  }
}

.encounter-card--kurumi {
  background: linear-gradient(135deg, rgba(72, 28, 48, 0.5), rgba(10, 14, 26, 0.7));
}

.encounter-card--yuno {
  background: linear-gradient(135deg, rgba(98, 32, 48, 0.48), rgba(10, 14, 26, 0.7));
}

.encounter-card--megumi {
  background: linear-gradient(135deg, rgba(48, 52, 62, 0.5), rgba(10, 14, 26, 0.7));
}

.encounter-card--mahiru {
  background: linear-gradient(135deg, rgba(52, 68, 98, 0.48), rgba(10, 14, 26, 0.7));
}

.encounter-card__avatar {
  flex-shrink: 0;
  width: 72px;
  border-radius: 14px;
  overflow: hidden;
  border: 1px solid rgba(255, 255, 255, 0.12);
}

.encounter-card__avatar img {
  width: 100%;
  aspect-ratio: 1;
  object-fit: cover;
  object-position: center top;
  filter: blur(0.8px) saturate(0.9);
  transition: filter 0.35s ease;
}

.encounter-card.is-active .encounter-card__avatar img {
  filter: none;
}

.encounter-card__fallback {
  width: 72px;
  height: 72px;
  display: grid;
  place-items: center;
  font-size: 0.65rem;
  color: rgba(255, 255, 255, 0.6);
}

.encounter-card__name {
  margin: 0 0 10px;
  font-family: var(--landing-font-display, serif);
  font-size: 1rem;
  font-weight: 600;
  color: rgba(255, 255, 255, 0.92);
}

.encounter-card__line {
  margin: 0;
  font-size: 0.88rem;
  line-height: 1.7;
  color: rgba(255, 255, 255, 0.78);
}

@media (max-width: 560px) {
  .encounter-cards {
    grid-template-columns: 1fr;
  }
}

@keyframes cardIn {
  from {
    opacity: 0;
    transform: translateY(16px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}
</style>
