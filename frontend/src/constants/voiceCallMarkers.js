/** Markers stored in message.audio_url for voice-call system rows. */
export const VOICE_CALL_TURN_AUDIO = 'system/voice-call-turn'
export const VOICE_CALL_SUMMARY_AUDIO = 'system/voice-call-summary'

export function isVoiceCallTurnMessage(msg) {
  return String(msg?.audioUrl || '') === VOICE_CALL_TURN_AUDIO
}

export function isVoiceCallSummaryMessage(msg) {
  return String(msg?.audioUrl || '') === VOICE_CALL_SUMMARY_AUDIO
}
