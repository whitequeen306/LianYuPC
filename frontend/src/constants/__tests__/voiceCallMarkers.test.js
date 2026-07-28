import { describe, expect, it } from 'vitest'
import {
  isVoiceCallTurnMessage,
  isVoiceCallSummaryMessage,
  VOICE_CALL_TURN_AUDIO,
  VOICE_CALL_SUMMARY_AUDIO,
} from '../voiceCallMarkers'

describe('voiceCallMarkers', () => {
  it('detects turn and summary markers', () => {
    expect(isVoiceCallTurnMessage({ audioUrl: VOICE_CALL_TURN_AUDIO })).toBe(true)
    expect(isVoiceCallSummaryMessage({ audioUrl: VOICE_CALL_SUMMARY_AUDIO })).toBe(true)
    expect(isVoiceCallTurnMessage({ audioUrl: VOICE_CALL_SUMMARY_AUDIO })).toBe(false)
    expect(isVoiceCallSummaryMessage({ audioUrl: 'pet/voice/raiden/x.mp3' })).toBe(false)
  })
})
