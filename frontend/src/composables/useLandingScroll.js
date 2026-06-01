import { onBeforeUnmount, onMounted, ref } from 'vue'

/**
 * Landing page: nav scroll state + section spy + smooth anchor scroll.
 */
export function useLandingScroll(sectionIds = []) {
  const activeSection = ref(sectionIds[0] ?? 'hero')
  const navScrolled = ref(false)

  let sectionObserver

  function scrollTo(id) {
    const el = document.getElementById(id)
    if (!el) return
    const top = el.getBoundingClientRect().top + window.scrollY - 72
    window.scrollTo({ top, behavior: 'smooth' })
  }

  function onWindowScroll() {
    navScrolled.value = window.scrollY > 24
  }

  onMounted(() => {
    onWindowScroll()
    window.addEventListener('scroll', onWindowScroll, { passive: true })

    sectionObserver = new IntersectionObserver(
      (entries) => {
        const visible = entries
          .filter((e) => e.isIntersecting)
          .sort((a, b) => b.intersectionRatio - a.intersectionRatio)
        if (visible[0]?.target?.id) {
          activeSection.value = visible[0].target.id
        }
      },
      { rootMargin: '-30% 0px -55% 0px', threshold: [0, 0.15, 0.4] },
    )

    sectionIds.forEach((id) => {
      const el = document.getElementById(id)
      if (el) sectionObserver.observe(el)
    })
  })

  onBeforeUnmount(() => {
    window.removeEventListener('scroll', onWindowScroll)
    sectionObserver?.disconnect()
  })

  return { activeSection, navScrolled, scrollTo }
}

export function useRevealOnScroll() {
  let revealObserver

  onMounted(() => {
    revealObserver = new IntersectionObserver(
      (entries) => {
        entries.forEach((entry) => {
          if (entry.isIntersecting) {
            entry.target.classList.add('reveal--visible')
            revealObserver.unobserve(entry.target)
          }
        })
      },
      { threshold: 0.12, rootMargin: '0px 0px -8% 0px' },
    )

    document.querySelectorAll('.reveal').forEach((el) => revealObserver.observe(el))
  })

  onBeforeUnmount(() => {
    revealObserver?.disconnect()
  })
}
