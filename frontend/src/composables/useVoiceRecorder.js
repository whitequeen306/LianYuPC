import { ref } from 'vue'

/**
 * Browser/Electron microphone capture via MediaRecorder.
 *
 * Two listen modes (intentionally different):
 * - startChunked: fixed-interval slices → chat mic streaming dictation (Cursor-like)
 * - startUtteranceLoop: speech→silence endpointing → one whole utterance (voice call)
 *
 * vadProfile:
 * - 'off'    — always send chunks
 * - 'loose'  — soft gate for chat dictation
 * - 'strict' — reject echo/noise for call
 */
const VAD_PROFILES = {
  off: null,
  loose: { speechLevel: 0.055, minSpeechRatio: 0.05, minPeakLevel: 0.06, minFrames: 3 },
  strict: { speechLevel: 0.12, minSpeechRatio: 0.16, minPeakLevel: 0.14, minFrames: 8 },
}

export function useVoiceRecorder() {
  const recording = ref(false)
  const audioLevel = ref(0)
  const speaking = ref(false)
  let mediaStream = null
  let mediaRecorder = null
  let chunks = []
  let chunkLoopActive = false
  let utteranceLoopActive = false
  let chunkTimer = null
  let chunkSession = 0
  let utteranceSession = 0
  let audioCtx = null
  let analyser = null
  let meterSource = null
  let levelRaf = null
  let chunkSpeechFrames = 0
  let chunkTotalFrames = 0
  let chunkPeakLevel = 0
  /** @type {{ speechLevel: number, minSpeechRatio: number, minPeakLevel: number, minFrames: number } | null} */
  let activeVad = VAD_PROFILES.strict

  function pickMime() {
    if (typeof MediaRecorder === 'undefined') return ''
    if (MediaRecorder.isTypeSupported('audio/webm;codecs=opus')) return 'audio/webm;codecs=opus'
    if (MediaRecorder.isTypeSupported('audio/webm')) return 'audio/webm'
    return ''
  }

  function resolveVadProfile(name) {
    if (name === false || name === 'off') return null
    if (name === true || name === 'strict') return VAD_PROFILES.strict
    if (name === 'loose') return VAD_PROFILES.loose
    return VAD_PROFILES.strict
  }

  function resetChunkVad() {
    chunkSpeechFrames = 0
    chunkTotalFrames = 0
    chunkPeakLevel = 0
  }

  function chunkVadPassed() {
    if (!activeVad) return true
    if (chunkTotalFrames < activeVad.minFrames) return false
    const ratio = chunkSpeechFrames / chunkTotalFrames
    return ratio >= activeVad.minSpeechRatio && chunkPeakLevel >= activeVad.minPeakLevel
  }

  function speechThreshold() {
    return activeVad?.speechLevel ?? 0.1
  }

  function stopMeter() {
    if (levelRaf != null) {
      cancelAnimationFrame(levelRaf)
      levelRaf = null
    }
    audioLevel.value = 0
    speaking.value = false
    try {
      meterSource?.disconnect()
    } catch { /* ignore */ }
    meterSource = null
    analyser = null
    if (audioCtx) {
      const ctx = audioCtx
      audioCtx = null
      void ctx.close().catch(() => {})
    }
  }

  function startMeter() {
    if (!mediaStream || levelRaf != null) return
    const Ctx = typeof window !== 'undefined'
      ? (window.AudioContext || window.webkitAudioContext)
      : null
    if (!Ctx) return
    try {
      audioCtx = new Ctx()
      analyser = audioCtx.createAnalyser()
      analyser.fftSize = 512
      analyser.smoothingTimeConstant = 0.55
      meterSource = audioCtx.createMediaStreamSource(mediaStream)
      meterSource.connect(analyser)
      const data = new Uint8Array(analyser.fftSize)
      const tick = () => {
        if (!analyser) return
        analyser.getByteTimeDomainData(data)
        let sum = 0
        for (let i = 0; i < data.length; i += 1) {
          const v = (data[i] - 128) / 128
          sum += v * v
        }
        const rms = Math.sqrt(sum / data.length)
        const level = Math.min(1, rms * 5.5)
        audioLevel.value = level
        const speechCut = speechThreshold()
        speaking.value = level > speechCut
        chunkTotalFrames += 1
        if (level > speechCut) chunkSpeechFrames += 1
        if (level > chunkPeakLevel) chunkPeakLevel = level
        levelRaf = requestAnimationFrame(tick)
      }
      if (audioCtx.state === 'suspended') {
        void audioCtx.resume().catch(() => {})
      }
      tick()
    } catch {
      stopMeter()
    }
  }

  async function ensureStream() {
    if (mediaStream) return mediaStream
    mediaStream = await navigator.mediaDevices.getUserMedia({
      audio: {
        echoCancellation: true,
        noiseSuppression: true,
        autoGainControl: true,
      },
    })
    startMeter()
    return mediaStream
  }

  function createRecorder() {
    const mime = pickMime()
    return mime
      ? new MediaRecorder(mediaStream, { mimeType: mime })
      : new MediaRecorder(mediaStream)
  }

  function sleep(ms) {
    return new Promise((resolve) => setTimeout(resolve, ms))
  }

  async function start() {
    if (recording.value) return
    chunks = []
    resetChunkVad()
    await ensureStream()
    mediaRecorder = createRecorder()
    mediaRecorder.ondataavailable = (e) => {
      if (e.data && e.data.size > 0) chunks.push(e.data)
    }
    mediaRecorder.start()
    recording.value = true
  }

  function stopTracks() {
    stopMeter()
    if (mediaStream) {
      mediaStream.getTracks().forEach((t) => t.stop())
    }
    mediaStream = null
    mediaRecorder = null
  }

  function stop() {
    return new Promise((resolve, reject) => {
      if (!mediaRecorder || !recording.value) {
        stopTracks()
        recording.value = false
        resolve(null)
        return
      }
      const recorder = mediaRecorder
      recorder.onstop = () => {
        recording.value = false
        const type = (recorder.mimeType || 'audio/webm').split(';')[0].trim() || 'audio/webm'
        const blob = chunks.length ? new Blob(chunks, { type }) : null
        chunks = []
        mediaRecorder = null
        resolve(blob)
      }
      recorder.onerror = () => {
        recording.value = false
        mediaRecorder = null
        chunks = []
        reject(new Error('录音失败'))
      }
      try {
        recorder.stop()
      } catch (err) {
        recording.value = false
        mediaRecorder = null
        chunks = []
        reject(err)
      }
    })
  }

  /**
   * Chat dictation: fixed-interval slices, append ASR text into composer.
   */
  async function startChunked({
    intervalMs = 1400,
    onChunk,
    awaitChunk = true,
    requireSpeech,
    vadProfile,
  } = {}) {
    if (chunkLoopActive || utteranceLoopActive) return
    chunkLoopActive = true
    const session = ++chunkSession
    if (vadProfile !== undefined) {
      activeVad = resolveVadProfile(vadProfile)
    } else if (requireSpeech === false) {
      activeVad = null
    } else {
      activeVad = VAD_PROFILES.loose
    }
    await ensureStream()
    recording.value = true

    const runOne = async () => {
      if (!chunkLoopActive || session !== chunkSession) return
      chunks = []
      resetChunkVad()
      mediaRecorder = createRecorder()
      const recorder = mediaRecorder
      const blob = await new Promise((resolve, reject) => {
        recorder.ondataavailable = (e) => {
          if (e.data && e.data.size > 0) chunks.push(e.data)
        }
        recorder.onerror = () => reject(new Error('录音失败'))
        recorder.onstop = () => {
          const type = (recorder.mimeType || 'audio/webm').split(';')[0].trim() || 'audio/webm'
          resolve(chunks.length ? new Blob(chunks, { type }) : null)
        }
        try {
          recorder.start()
        } catch (err) {
          reject(err)
        }
        chunkTimer = setTimeout(() => {
          try {
            if (recorder.state !== 'inactive') recorder.stop()
          } catch {
            resolve(null)
          }
        }, Math.max(900, intervalMs))
      })
      mediaRecorder = null
      chunks = []
      if (!chunkLoopActive || session !== chunkSession) return
      const vadOk = chunkVadPassed()
      if (blob && blob.size >= 16 && vadOk && typeof onChunk === 'function') {
        const task = Promise.resolve()
          .then(() => onChunk(blob, {
            speechRatio: chunkTotalFrames ? chunkSpeechFrames / chunkTotalFrames : 0,
            peakLevel: chunkPeakLevel,
          }))
          .catch(() => {})
        if (awaitChunk) await task
      }
      if (chunkLoopActive && session === chunkSession) {
        await Promise.resolve()
        return runOne()
      }
    }

    try {
      await runOne()
    } catch (err) {
      chunkLoopActive = false
      recording.value = false
      stopTracks()
      throw err
    }
  }

  async function stopChunked() {
    chunkLoopActive = false
    chunkSession += 1
    if (chunkTimer) {
      clearTimeout(chunkTimer)
      chunkTimer = null
    }
    if (mediaRecorder && mediaRecorder.state !== 'inactive') {
      try {
        mediaRecorder.stop()
      } catch {
        // ignore
      }
    }
    recording.value = false
    chunks = []
    stopTracks()
  }

  /**
   * Voice call: wait for speech, record until trailing silence (whole utterance), then onUtterance.
   */
  async function startUtteranceLoop({
    onUtterance,
    vadProfile = 'strict',
    silenceMs = 750,
    minSpeechMs = 300,
    maxUtteranceMs = 12000,
    pollMs = 40,
  } = {}) {
    if (utteranceLoopActive || chunkLoopActive) return
    utteranceLoopActive = true
    const session = ++utteranceSession
    activeVad = resolveVadProfile(vadProfile)
    await ensureStream()
    recording.value = true

    const isLive = () => utteranceLoopActive && session === utteranceSession

    const waitForSpeech = async () => {
      let voicedMs = 0
      while (isLive()) {
        if (speaking.value || audioLevel.value >= speechThreshold()) {
          voicedMs += pollMs
          if (voicedMs >= minSpeechMs) return true
        } else {
          voicedMs = 0
        }
        await sleep(pollMs)
      }
      return false
    }

    const recordUntilSilence = async () => {
      chunks = []
      resetChunkVad()
      mediaRecorder = createRecorder()
      const recorder = mediaRecorder
      const startedAt = Date.now()
      let silentMs = 0
      let hadSpeech = false

      const blobPromise = new Promise((resolve, reject) => {
        recorder.ondataavailable = (e) => {
          if (e.data && e.data.size > 0) chunks.push(e.data)
        }
        recorder.onerror = () => reject(new Error('录音失败'))
        recorder.onstop = () => {
          const type = (recorder.mimeType || 'audio/webm').split(';')[0].trim() || 'audio/webm'
          resolve(chunks.length ? new Blob(chunks, { type }) : null)
        }
        try {
          recorder.start()
        } catch (err) {
          reject(err)
        }
      })

      while (isLive() && recorder.state !== 'inactive') {
        const level = audioLevel.value
        const isSpeech = speaking.value || level >= speechThreshold()
        if (isSpeech) {
          hadSpeech = true
          silentMs = 0
        } else if (hadSpeech) {
          silentMs += pollMs
          if (silentMs >= silenceMs) break
        }
        if (Date.now() - startedAt >= maxUtteranceMs) break
        await sleep(pollMs)
      }

      try {
        if (recorder.state !== 'inactive') recorder.stop()
      } catch {
        // ignore
      }
      mediaRecorder = null
      const blob = await blobPromise
      chunks = []
      return hadSpeech ? blob : null
    }

    try {
      while (isLive()) {
        const gotSpeech = await waitForSpeech()
        if (!gotSpeech || !isLive()) break
        const blob = await recordUntilSilence()
        if (!isLive()) break
        if (blob && blob.size >= 16 && typeof onUtterance === 'function') {
          try {
            await onUtterance(blob, {
              peakLevel: chunkPeakLevel,
              speechRatio: chunkTotalFrames ? chunkSpeechFrames / chunkTotalFrames : 0,
            })
          } catch {
            // keep listening
          }
        }
      }
    } catch (err) {
      utteranceLoopActive = false
      recording.value = false
      stopTracks()
      throw err
    }
  }

  async function stopUtteranceLoop() {
    utteranceLoopActive = false
    utteranceSession += 1
    if (mediaRecorder && mediaRecorder.state !== 'inactive') {
      try {
        mediaRecorder.stop()
      } catch {
        // ignore
      }
    }
    recording.value = false
    chunks = []
    stopTracks()
  }

  function cancel() {
    chunkLoopActive = false
    utteranceLoopActive = false
    chunkSession += 1
    utteranceSession += 1
    if (chunkTimer) {
      clearTimeout(chunkTimer)
      chunkTimer = null
    }
    if (mediaRecorder && mediaRecorder.state !== 'inactive') {
      try { mediaRecorder.stop() } catch { /* ignore */ }
    }
    chunks = []
    recording.value = false
    stopTracks()
  }

  return {
    recording,
    audioLevel,
    speaking,
    start,
    stop,
    cancel,
    startChunked,
    stopChunked,
    startUtteranceLoop,
    stopUtteranceLoop,
  }
}
