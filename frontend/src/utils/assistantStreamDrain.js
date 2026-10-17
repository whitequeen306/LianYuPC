import { createSseAccumulator } from '../../shared/sseParser'

/**
 * Read SSE assistant reply from a fetch Response body.
 * 解析逻辑单源于 shared/sseParser（主进程 qqBridge/wechatBridge 同源）。
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
  const acc = createSseAccumulator()

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
        acc.feed(line)
      }
    }
  } finally {
    reader.releaseLock()
  }

  return acc.result()
}
