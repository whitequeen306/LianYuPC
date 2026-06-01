import { nextTick, onBeforeUnmount, onMounted } from 'vue'
import gsap from 'gsap'
import { ScrollTrigger } from 'gsap/ScrollTrigger'

let pluginsRegistered = false

function registerPlugins() {
  if (!pluginsRegistered) {
    gsap.registerPlugin(ScrollTrigger)
    pluginsRegistered = true
  }
}

function prefersReducedMotion() {
  return window.matchMedia('(prefers-reduced-motion: reduce)').matches
}

const REVEAL_FROM = { y: 28, autoAlpha: 0, duration: 0.85, ease: 'power3.out' }
const REVEAL_STAGGER = { y: 36, autoAlpha: 0, duration: 0.7, ease: 'power3.out' }

/**
 * Landing page GSAP: hero entrance timeline (Phase 2) + ScrollTrigger section reveals (Phase 3).
 * Scoped with gsap.context; respects prefers-reduced-motion.
 */
export function useLandingGsap(rootRef) {
  let ctx

  function runHeroTimeline(root) {
    const nav = root.querySelector('.landing-nav')
    const hero = root.querySelector('#hero')
    if (!hero) return

    const tl = gsap.timeline({ defaults: { ease: 'power3.out' } })

    if (nav) {
      tl.from(nav, { y: -16, autoAlpha: 0, duration: 0.55 }, 0)
    }

    const heroItems = hero.querySelectorAll('[data-gsap-hero]')
    if (heroItems.length) {
      tl.from(
        heroItems,
        {
          y: 32,
          autoAlpha: 0,
          duration: 0.75,
          stagger: 0.1,
        },
        nav ? 0.12 : 0,
      )
    }

    const scrollHint = hero.querySelector('.hero__scroll')
    if (scrollHint) {
      tl.from(scrollHint, { autoAlpha: 0, y: 12, duration: 0.5 }, '-=0.35')
      gsap.to(scrollHint, {
        y: 8,
        duration: 1.4,
        ease: 'sine.inOut',
        repeat: -1,
        yoyo: true,
      })
    }

    const orbs = root.querySelectorAll('.landing__orb')
    if (orbs.length) {
      gsap.to(orbs, {
        x: '+=12',
        y: '+=-8',
        duration: 10,
        ease: 'sine.inOut',
        stagger: 0.4,
        repeat: -1,
        yoyo: true,
      })
    }
  }

  function runScrollReveals(root) {
    const sectionHeads = root.querySelectorAll('.section__head.gsap-reveal')
    sectionHeads.forEach((head) => {
      gsap.from(head, {
        ...REVEAL_FROM,
        scrollTrigger: {
          trigger: head,
          start: 'top 84%',
          once: true,
        },
      })
    })

    const revealBlocks = root.querySelectorAll('.gsap-reveal-block')
    revealBlocks.forEach((block) => {
      gsap.from(block, {
        ...REVEAL_FROM,
        scrollTrigger: {
          trigger: block,
          start: 'top 86%',
          once: true,
        },
      })
    })

    const featureCards = root.querySelectorAll('.feature-card')
    if (featureCards.length) {
      gsap.set(featureCards, { autoAlpha: 0, y: 40 })
      ScrollTrigger.batch(featureCards, {
        start: 'top 88%',
        once: true,
        onEnter: (elements) => {
          gsap.to(elements, {
            autoAlpha: 1,
            y: 0,
            duration: 0.65,
            stagger: 0.07,
            ease: 'power3.out',
            overwrite: 'auto',
          })
        },
      })
    }

    const flowSteps = root.querySelectorAll('.flow-step')
    if (flowSteps.length) {
      gsap.set(flowSteps, { autoAlpha: 0, y: 32 })
      ScrollTrigger.batch(flowSteps, {
        start: 'top 90%',
        once: true,
        onEnter: (elements) => {
          gsap.to(elements, {
            autoAlpha: 1,
            y: 0,
            duration: 0.6,
            stagger: 0.12,
            ease: 'power3.out',
            overwrite: 'auto',
          })
        },
      })
    }

    const castListItems = root.querySelectorAll('.cast-section__list li')
    if (castListItems.length) {
      gsap.set(castListItems, { autoAlpha: 0, x: -16 })
      ScrollTrigger.batch(castListItems, {
        start: 'top 92%',
        once: true,
        onEnter: (elements) => {
          gsap.to(elements, {
            autoAlpha: 1,
            x: 0,
            duration: 0.5,
            stagger: 0.08,
            ease: 'power2.out',
          })
        },
      })
    }

    const thanksChips = root.querySelectorAll('.thanks__chip')
    if (thanksChips.length) {
      gsap.set(thanksChips, { autoAlpha: 0, scale: 0.92 })
      ScrollTrigger.batch(thanksChips, {
        start: 'top 92%',
        once: true,
        onEnter: (elements) => {
          gsap.to(elements, {
            autoAlpha: 1,
            scale: 1,
            duration: 0.45,
            stagger: 0.06,
            ease: 'back.out(1.4)',
          })
        },
      })
    }
  }

  function runMicroInteractions(root) {
    const ctaButtons = root.querySelectorAll('.section--cta .btn')
    ctaButtons.forEach((btn) => {
      btn.addEventListener('mouseenter', () => {
        gsap.to(btn, { scale: 1.03, duration: 0.25, ease: 'power2.out', overwrite: 'auto' })
      })
      btn.addEventListener('mouseleave', () => {
        gsap.to(btn, { scale: 1, duration: 0.25, ease: 'power2.out', overwrite: 'auto' })
      })
    })

    const featureCards = root.querySelectorAll('.feature-card')
    featureCards.forEach((card) => {
      card.addEventListener('mouseenter', () => {
        gsap.to(card, { y: -6, duration: 0.35, ease: 'power2.out', overwrite: 'auto' })
      })
      card.addEventListener('mouseleave', () => {
        gsap.to(card, { y: 0, duration: 0.35, ease: 'power2.out', overwrite: 'auto' })
      })
    })
  }

  onMounted(async () => {
    registerPlugins()
    await nextTick()

    const root = rootRef?.value
    if (!root) return

    if (prefersReducedMotion()) {
      root.classList.add('landing--reduced-motion')
      return
    }

    root.classList.add('landing--gsap-active')

    ctx = gsap.context(() => {
      runHeroTimeline(root)
      runScrollReveals(root)
      runMicroInteractions(root)
    }, root)

    requestAnimationFrame(() => ScrollTrigger.refresh())
    const onLoad = () => ScrollTrigger.refresh()
    window.addEventListener('load', onLoad, { once: true })

    onBeforeUnmount(() => {
      window.removeEventListener('load', onLoad)
    })
  })

  onBeforeUnmount(() => {
    ctx?.revert()
    rootRef?.value?.classList.remove('landing--gsap-active', 'landing--reduced-motion')
  })
}
