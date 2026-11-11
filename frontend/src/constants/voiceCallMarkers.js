/** Markers stored in message.audio_url for voice-call system rows. */
export const VOICE_CALL_TURN_AUDIO = 'system/voice-call-turn'
export const VOICE_CALL_SUMMARY_AUDIO = 'system/voice-call-summary'
/** 主动来电邀请：只是一张卡片，点了才真正开麦。 */
export const VOICE_CALL_INVITE_AUDIO = 'system/voice-call-invite'

export function isVoiceCallTurnMessage(msg) {
  return String(msg?.audioUrl || '') === VOICE_CALL_TURN_AUDIO
}

export function isVoiceCallSummaryMessage(msg) {
  return String(msg?.audioUrl || '') === VOICE_CALL_SUMMARY_AUDIO
}

export function isVoiceCallInviteMessage(msg) {
  return String(msg?.audioUrl || '') === VOICE_CALL_INVITE_AUDIO
}
