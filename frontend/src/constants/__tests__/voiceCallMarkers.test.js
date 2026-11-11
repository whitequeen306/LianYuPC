import { describe, expect, it } from 'vitest'
import {
  isVoiceCallTurnMessage,
  isVoiceCallSummaryMessage,
  isVoiceCallInviteMessage,
  VOICE_CALL_TURN_AUDIO,
  VOICE_CALL_SUMMARY_AUDIO,
  VOICE_CALL_INVITE_AUDIO,
} from '../voiceCallMarkers'

describe('voiceCallMarkers', () => {
  it('detects turn and summary markers', () => {
    expect(isVoiceCallTurnMessage({ audioUrl: VOICE_CALL_TURN_AUDIO })).toBe(true)
    expect(isVoiceCallSummaryMessage({ audioUrl: VOICE_CALL_SUMMARY_AUDIO })).toBe(true)
    expect(isVoiceCallTurnMessage({ audioUrl: VOICE_CALL_SUMMARY_AUDIO })).toBe(false)
    expect(isVoiceCallSummaryMessage({ audioUrl: 'pet/voice/raiden/x.mp3' })).toBe(false)
  })

  it('detects the call-invite marker on its own', () => {
    expect(isVoiceCallInviteMessage({ audioUrl: VOICE_CALL_INVITE_AUDIO })).toBe(true)
    // 三种标记互斥：邀请不能被当成回合（回合会被跳过不渲染）或摘要
    expect(isVoiceCallTurnMessage({ audioUrl: VOICE_CALL_INVITE_AUDIO })).toBe(false)
    expect(isVoiceCallSummaryMessage({ audioUrl: VOICE_CALL_INVITE_AUDIO })).toBe(false)
    expect(isVoiceCallInviteMessage({ audioUrl: VOICE_CALL_SUMMARY_AUDIO })).toBe(false)
    expect(isVoiceCallInviteMessage({})).toBe(false)
  })
})
