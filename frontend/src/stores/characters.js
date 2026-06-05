import { defineStore } from 'pinia'
import { ref } from 'vue'
import {
  listCharacters,
  createCharacter as apiCreateCharacter,
  deleteCharacter as apiDeleteCharacter,
  updateCharacter as apiUpdateCharacter
} from '@/api/character'
import { sanitizeCharacter, sanitizeCharacterSettings } from '@/utils/textEncoding'

/** 会话内列表缓存 TTL（毫秒），写操作会立即失效 */
const STALE_MS = 60_000

export const useCharactersStore = defineStore('characters', () => {
  const list = ref([])
  const loading = ref(false)
  let lastFetchedAt = 0

  function invalidate() {
    lastFetchedAt = 0
  }

  function isFresh() {
    return list.value.length > 0 && Date.now() - lastFetchedAt < STALE_MS
  }

  async function fetchList({ force = false } = {}) {
    if (!force && isFresh()) {
      return list.value
    }
    loading.value = true
    try {
      const data = await listCharacters()
      list.value = Array.isArray(data) ? data.map(sanitizeCharacter) : []
      lastFetchedAt = Date.now()
      return list.value
    } finally {
      loading.value = false
    }
  }

  async function create(data) {
    const payload = {
      ...data,
      name: data?.name,
      settings: sanitizeCharacterSettings(data?.settings),
    }
    const created = await apiCreateCharacter(payload)
    invalidate()
    await fetchList({ force: true })
    return sanitizeCharacter(created)
  }

  async function remove(id) {
    await apiDeleteCharacter(id)
    list.value = list.value.filter(c => c.id !== id)
    lastFetchedAt = Date.now()
  }

  async function update(id, data) {
    const payload = data?.settings
      ? { ...data, settings: sanitizeCharacterSettings(data.settings) }
      : data
    const updated = await apiUpdateCharacter(id, payload)
    const normalized = sanitizeCharacter(updated)
    const idx = list.value.findIndex(c => c.id === id)
    if (idx >= 0) {
      list.value[idx] = { ...list.value[idx], ...normalized }
    }
    lastFetchedAt = Date.now()
    return normalized
  }

  function upsertLocal(character) {
    if (!character?.id) return
    const normalized = sanitizeCharacter(character)
    const idx = list.value.findIndex(c => c.id === normalized.id)
    if (idx >= 0) {
      list.value[idx] = { ...list.value[idx], ...normalized }
    } else {
      list.value = [normalized, ...list.value]
    }
    lastFetchedAt = Date.now()
  }

  return {
    list,
    loading,
    invalidate,
    fetchList,
    create,
    remove,
    update,
    upsertLocal
  }
})
