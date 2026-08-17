import { describe, expect, it } from 'vitest'
import {
  buildImportCreatePayload,
  isAllowedImportFile,
  mergeAddressingIntoPrompt,
} from '@/utils/characterImport'

describe('characterImport', () => {
  it('accepts text-like files and rejects images', () => {
    expect(isAllowedImportFile({ name: 'kurumi.txt', type: 'text/plain' })).toBe(true)
    expect(isAllowedImportFile({ name: 'card.json', type: 'application/json' })).toBe(true)
    expect(isAllowedImportFile({ name: 'wechat.html', type: 'text/html' })).toBe(true)
    expect(isAllowedImportFile({ name: 'avatar.png', type: 'image/png' })).toBe(false)
  })

  it('maps draft JSON onto create payload without conversation messages', () => {
    const payload = buildImportCreatePayload({
      draft: {
        name: '时崎狂三',
        age: '17',
        gender: '女',
        speakingStyle: '傲娇',
        personalityArchetype: 'tsundere',
        promptTemplate: '性格定位：傲娇（tsundere）\n嘴硬但会关心你。',
        sourceType: 'chat_log',
      },
      city: '上海',
      userAddressing: '人类',
    })

    expect(payload.name).toBe('时崎狂三')
    expect(payload.settings.city).toBe('上海')
    expect(payload.settings.userAddressing).toBe('人类')
    expect(payload.settings.sourceType).toBe('chat_log')
    expect(payload.promptTemplate).toContain('最常用的称呼是「人类」')
    expect(payload.messages).toBeUndefined()
  })

  it('does not duplicate addressing hint', () => {
    const once = mergeAddressingIntoPrompt('人设正文', '笨蛋')
    expect(mergeAddressingIntoPrompt(once, '笨蛋')).toBe(once)
    expect(mergeAddressingIntoPrompt('人设正文', '「笨蛋」')).toContain('最常用的称呼是「笨蛋」')
  })
})
