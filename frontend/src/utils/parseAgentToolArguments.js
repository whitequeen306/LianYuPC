/**
 * 解析云端下发的本地工具参数。
 * Spring AI / STOMP 可能把 arguments 做成 JSON 字符串、已经是对象、
 * 或模型直接塞一整句任务（非 JSON）。空对象会导致引擎「缺少 instruction」
 * 并在 1 秒内失败、本机日志里看不到 computer_task start。
 */
const INSTRUCTION_KEYS = ['instruction', 'task', 'query', 'prompt', 'input', 'text']

export function parseAgentToolArguments(raw) {
  if (raw == null || raw === '') return {}
  if (typeof raw === 'object' && !Array.isArray(raw)) {
    return coerceInstruction(raw)
  }
  if (typeof raw !== 'string') return {}
  const trimmed = raw.trim()
  if (!trimmed) return {}
  try {
    const parsed = JSON.parse(trimmed)
    if (typeof parsed === 'string') return coerceInstruction({ instruction: parsed })
    if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) {
      return coerceInstruction(parsed)
    }
  } catch {
    return { instruction: trimmed }
  }
  return { instruction: trimmed }
}

function coerceInstruction(obj) {
  const found = INSTRUCTION_KEYS
    .map((key) => obj[key])
    .find((value) => typeof value === 'string' && value.trim())
  if (!found) return obj
  const instruction = found.trim()
  if (obj.instruction === instruction) return obj
  return { ...obj, instruction }
}
