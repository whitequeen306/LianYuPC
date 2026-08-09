import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { addCharacterFromSquare, listCharacterSquareTemplates, toggleSquareLike } from '@/api/characterSquare'
import { useCharactersStore } from '@/stores/characters'

const STALE_MS = 120_000

function cacheKey(page, tag, keyword) {
  return `${page}|${tag || ''}|${keyword || ''}`
}

/** Backend already sorts by likeCount → sortOrder → id; preserve order. */
function patchRecords(records, patchRecord) {
  return records.map(patchRecord)
}

export const useCharacterSquareStore = defineStore('characterSquare', () => {
  const pages = ref({})
  const loading = ref(false)
  const userLikes = ref(new Set())
  const catalogRecords = ref([])

  function invalidateAll() {
    pages.value = {}
  }

  function getCached(page, tag, keyword) {
    const entry = pages.value[cacheKey(page, tag, keyword)]
    if (!entry) return null
    if (Date.now() - entry.fetchedAt > STALE_MS) {
      return null
    }
    return entry
  }

  async function fetchTemplates({ page = 1, size = 12, tag = '', keyword = '', force = false } = {}) {
    const key = cacheKey(page, tag, keyword)
    if (!force) {
      const hit = getCached(page, tag, keyword)
      if (hit) {
        return hit
      }
    }

    loading.value = true
    try {
      const data = await listCharacterSquareTemplates({
        page,
        size,
        tag: tag || undefined,
        keyword: keyword || undefined,
      })
      const records = data?.records || []
      if (Array.isArray(data?.userLikes)) {
        userLikes.value = new Set(data.userLikes)
      } else {
        userLikes.value = new Set(
          records.filter(item => item.liked).map(item => item.id),
        )
      }
      if (page === 1 && !tag && !keyword) {
        catalogRecords.value = records
      }
      const entry = {
        records,
        total: data?.total ?? 0,
        tags: data?.tags || [],
        page: data?.page ?? page,
        userLikes: [...userLikes.value],
        fetchedAt: Date.now()
      }
      pages.value = { ...pages.value, [key]: entry }
      return entry
    } finally {
      loading.value = false
    }
  }

  const top3TemplateIds = computed(() => {
    return catalogRecords.value
      .slice(0, 3)
      .map(item => item.id)
  })

  async function addTemplate(templateId, { city } = {}) {
    const payload = { cityMode: 'real', city }
    const created = await addCharacterFromSquare(templateId, payload)
    invalidateAll()
    useCharactersStore().invalidate()
    return created
  }

  async function toggleLike(templateId) {
    const result = await toggleSquareLike(templateId)
    const liked = !!result?.liked
    const likeCount = result?.likeCount ?? 0

    if (liked) {
      userLikes.value.add(templateId)
    } else {
      userLikes.value.delete(templateId)
    }
    userLikes.value = new Set(userLikes.value)

    const patchRecord = item =>
      item.id === templateId
        ? { ...item, liked, likeCount }
        : item

    catalogRecords.value = patchRecords(catalogRecords.value, patchRecord)

    const next = { ...pages.value }
    for (const key of Object.keys(next)) {
      const entry = next[key]
      if (!entry?.records?.length) continue
      next[key] = {
        ...entry,
        userLikes: [...userLikes.value],
        records: patchRecords(entry.records, patchRecord)
      }
    }
    pages.value = next

    return result
  }

  function markAddedInCache(templateId, characterId) {
    const next = { ...pages.value }
    for (const key of Object.keys(next)) {
      const entry = next[key]
      if (!entry?.records?.length) continue
      next[key] = {
        ...entry,
        records: entry.records.map(item =>
          item.id === templateId
            ? { ...item, added: true, addedCharacterId: characterId ?? item.addedCharacterId }
            : item
        )
      }
    }
    pages.value = next
    catalogRecords.value = catalogRecords.value.map(item =>
      item.id === templateId
        ? { ...item, added: true, addedCharacterId: characterId ?? item.addedCharacterId }
        : item
    )
  }

  function isMostLiked(templateId, scopedRecords = catalogRecords.value) {
    return top3TemplateIds.value.includes(templateId)
      && scopedRecords.some(item => item.id === templateId)
  }

  return {
    pages,
    loading,
    userLikes,
    catalogRecords,
    top3TemplateIds,
    invalidateAll,
    fetchTemplates,
    addTemplate,
    toggleLike,
    markAddedInCache,
    isMostLiked
  }
})
