import { onMounted, onUnmounted } from 'vue'
import { gsap } from 'gsap'
import { isElectronRuntime } from '@/utils/runtime'

/**
 * 登录/注册页入场与背景光晕微动（scoped 到 pageRef 根节点）
 */
export function useAuthPageGsap(pageRef) {
  let ctx

  onMounted(() => {
    const root = pageRef.value
    if (!root) return

    const reduced = window.matchMedia('(prefers-reduced-motion: reduce)').matches
    if (reduced || isElectronRuntime()) {
      gsap.set(root.querySelectorAll('.auth-container, .form-reveal, .auth-art .art-icon, .auth-art .art-title, .auth-art .art-subtitle, .auth-art .art-decorations'), { autoAlpha: 1, opacity: 1, y: 0, scale: 1, scaleX: 1 })
      return
    }

    ctx = gsap.context(() => {
      gsap.from('.auth-container', {
        autoAlpha: 0,
        y: 32,
        scale: 0.94,
        duration: 1,
        ease: 'power3.out',
      })

      gsap.from('.auth-art .art-icon', {
        scale: 0,
        rotation: -18,
        duration: 0.75,
        delay: 0.15,
        ease: 'back.out(1.7)',
      })

      gsap.from('.auth-art .art-title', {
        autoAlpha: 0,
        y: 24,
        duration: 0.65,
        delay: 0.28,
        ease: 'power2.out',
      })

      gsap.from('.auth-art .art-subtitle', {
        autoAlpha: 0,
        y: 16,
        duration: 0.55,
        delay: 0.38,
        ease: 'power2.out',
      })

      gsap.from('.auth-art .art-decorations', {
        autoAlpha: 0,
        scaleX: 0,
        duration: 0.5,
        delay: 0.48,
        ease: 'power2.out',
      })

      gsap.from('.auth-form-panel .form-reveal', {
        autoAlpha: 0,
        y: 18,
        duration: 0.5,
        stagger: 0.07,
        delay: 0.42,
        ease: 'power2.out',
      })

      gsap.to('.bg-orb', {
        y: '+=20',
        x: '+=8',
        duration: 5,
        ease: 'sine.inOut',
        yoyo: true,
        repeat: -1,
        stagger: { each: 1.2, from: 'random' },
      })

      gsap.to('.auth-art .art-ring', {
        rotation: 360,
        duration: 48,
        ease: 'none',
        repeat: -1,
      })
    }, root)
  })

  onUnmounted(() => {
    ctx?.revert()
  })
}
