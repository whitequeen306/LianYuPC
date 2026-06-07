import { useRouter } from 'vue-router'
import { createConversation } from '@/api/conversation'
import { useConversationsStore } from '@/stores/conversations'

/** Find or create a SINGLE conversation, then navigate to chat. */
export function useOpenSingleChat() {
  const router = useRouter()
  const conversationsStore = useConversationsStore()

  async function openSingleChat(characterId, { force = false, fallbackPath = '/app/characters' } = {}) {
    if (!characterId) return null
    try {
      const convs = await conversationsStore.fetchList(force ? { force: true } : undefined)
      const existing = (convs || []).find(c => c.mode === 'SINGLE' && c.characterId === characterId)
      const convId = existing?.id ?? (await createConversation({ characterId, mode: 'SINGLE' })).id
      await router.push({ path: `/app/chat/${convId}` })
      return convId
    } catch {
      await router.push(fallbackPath)
      return null
    }
  }

  return { openSingleChat }
}
