import { ref } from 'vue'

/**
 * Browser/Electron microphone capture via MediaRecorder.
 * Supports one-shot record and continuous sentence-chunk capture.
 */
export function useVoiceRecorder() {
  const recording = ref(false)
  let mediaStream = null
  let mediaRecorder = null
  let chunks = []
  let chunkLoopActive = false
  let chunkTimer = null
  let chunkSession = 0

  function pickMime() {
    if (typeof MediaRecorder === 'undefined') return ''
    if (MediaRecorder.isTypeSupported('audio/webm;codecs=opus')) return 'audio/webm;codecs=opus'
    if (MediaRecorder.isTypeSupported('audio/webm')) return 'audio/webm'
    return ''
  }

  async function ensureStream() {
    if (mediaStream) return mediaStream
    mediaStream = await navigator.mediaDevices.getUserMedia({ audio: true })
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
        const type = recorder.mimeType || 'audio/webm'
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
  async function startChunked({ intervalMs = 2800, onChunk } = {}) {
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
          const type = recorder.mimeType || 'audio/webm'
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
        }, Math.max(1200, intervalMs))
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
        // yield a tick then continue
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
        // Trigger existing onstop from the active chunk promise (do not overwrite it)
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

  return { recording, start, stop, cancel, startChunked, stopChunked }
}
