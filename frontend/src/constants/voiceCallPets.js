/** Pet ids allowed for duplex voice call (must match VoiceCallService.VOICE_CALL_PET_IDS). */
export const VOICE_CALL_PET_IDS = Object.freeze([
  'klee',
  'ganyu',
  'ayaka',
  'raiden',
  'elysia',
  'erii_uesugi',
  'yae_miko',
  'kokomi',
  'shenhe',
  'nahida',
  'hu_tao',
  'furina',
  'noelle',
])

export function isVoiceCallPet(petId) {
  if (!petId || typeof petId !== 'string') return false
  return VOICE_CALL_PET_IDS.includes(petId.trim().toLowerCase())
}
