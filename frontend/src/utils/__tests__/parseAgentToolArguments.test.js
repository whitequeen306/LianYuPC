import { describe, it, expect } from 'vitest'
import { parseAgentToolArguments } from '../parseAgentToolArguments.js'

describe('parseAgentToolArguments', () => {
  it('parses a JSON string with instruction', () => {
    expect(parseAgentToolArguments('{"instruction":"打开网易云"}'))
      .toEqual({ instruction: '打开网易云' })
  })

  it('accepts an already-parsed object', () => {
    expect(parseAgentToolArguments({ instruction: '放《水手》' }))
      .toEqual({ instruction: '放《水手》' })
  })

  it('promotes task/query aliases to instruction', () => {
    expect(parseAgentToolArguments({ task: '打开网易云' }))
      .toEqual({ task: '打开网易云', instruction: '打开网易云' })
    expect(parseAgentToolArguments('{"query":"播放水手"}'))
      .toEqual({ query: '播放水手', instruction: '播放水手' })
  })

  it('treats a plain string as the instruction', () => {
    expect(parseAgentToolArguments('打开网易云来首水手')).toEqual({
      instruction: '打开网易云来首水手',
    })
  })

  it('returns empty for blank input', () => {
    expect(parseAgentToolArguments('')).toEqual({})
    expect(parseAgentToolArguments(null)).toEqual({})
  })
})
