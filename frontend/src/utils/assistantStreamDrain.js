/**
 * Read SSE assistant reply from a fetch Response body.
 * @param {Response} response
 * @param {{ signal?: AbortSignal }} [options]
 * @returns {Promise<{ fullContent: string, pieces: string[] | null }>}
 */
export async function drainAssistantStream(response, options = {}) {
  const { signal } = options
  const reader = response.body?.getReader()
  if (!reader) {
    throw new Error('无法读取回复流')
  }
  const decoder = new TextDecoder()
  let buffer = ''
  let fullContent = ''
  let serverPieces = null

  try {
    while (true) {
      if (signal?.aborted) {
        await reader.cancel()
        throw new DOMException('Aborted', 'AbortError')
      }
      const { done, value } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      const parts = buffer.split('\n')
      buffer = parts.pop() || ''

      for (const line of parts) {
        const trimmed = line.trim()
        if (!trimmed.startsWith('data:')) continue
        const data = trimmed.slice(5).trim()
        if (!data || data === '[DONE]') continue
        try {
          const payload = JSON.parse(data)
          if (payload.error) {
            throw new Error(payload.error)
          }
          if (payload.content) {
            fullContent += payload.content
          }
          if (payload.replace) {
            fullContent = payload.replace
          }
          if (Array.isArray(payload.pieces) && payload.pieces.length) {
            serverPieces = payload.pieces.map(p => String(p ?? '').trim()).filter(Boolean)
          }
        } catch (e) {
          if (e instanceof SyntaxError) continue
          throw e
        }
      }
    }
  } finally {
    reader.releaseLock()
  }

  return { fullContent, pieces: serverPieces }
}
