import { ref } from 'vue'

/**
 * Browser/Electron microphone capture via MediaRecorder.
 * Supports one-shot record, continuous sentence-chunk capture, and live level metering.
 */
export function useVoiceRecorder() {
  const recording = ref(false)
  const audioLevel = ref(0)
  const speaking = ref(false)
  let mediaStream = null
  let mediaRecorder = null
  let chunks = []
  let chunkLoopActive = false
  let chunkTimer = null
  let chunkSession = 0
  let audioCtx = null
  let analyser = null
  let meterSource = null
  let levelRaf = null

  function pickMime() {
    if (typeof MediaRecorder === 'undefined') return ''
    if (MediaRecorder.isTypeSupported('audio/webm;codecs=opus')) return 'audio/webm;codecs=opus'
    if (MediaRecorder.isTypeSupported('audio/webm')) return 'audio/webm'
    return ''
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
      analyser.fftSize = 256
      analyser.smoothingTimeConstant = 0.72
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
        const level = Math.min(1, rms * 5.2)
        audioLevel.value = level
        speaking.value = level > 0.06
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
    mediaStream = await navigator.mediaDevices.getUserMedia({ audio: true })
    startMeter()
    return mediaStream
  }

  function createRecorder() {
    const mime = pickMime()
    return mime
      ? new MediaRecorder(mediaStream, { mimeType: mime })
      : new MediaRecorder(mediaStream)
  }

  async function start() {
    if (recording.value) return
    chunks = []
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
   * Continuous listen: every intervalMs cut a blob and invoke onChunk.
   * Does not stop the mic stream between chunks (reuses getUserMedia).
   */
  async function startChunked({ intervalMs = 2200, onChunk } = {}) {
    if (chunkLoopActive) return
    chunkLoopActive = true
    const session = ++chunkSession
    await ensureStream()
    recording.value = true

    const runOne = async () => {
      if (!chunkLoopActive || session !== chunkSession) return
      chunks = []
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
        }, Math.max(1100, intervalMs))
      })
      mediaRecorder = null
      chunks = []
      if (!chunkLoopActive || session !== chunkSession) return
      if (blob && blob.size >= 16 && typeof onChunk === 'function') {
        try {
          await onChunk(blob)
        } catch {
          // caller handles toast; keep listening
        }
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

  function cancel() {
    chunkLoopActive = false
    chunkSession += 1
    if (chunkTimer) {
      clearTimeout(chunkTimer)
      chunkTimer = null
    }
    if (mediaRecorder && recording.value) {
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
  }
}
