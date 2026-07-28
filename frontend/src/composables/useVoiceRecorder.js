import { ref } from 'vue'

/**
 * Browser/Electron microphone capture via MediaRecorder.
 * Returns webm/opus blob on stop().
 */
export function useVoiceRecorder() {
  const recording = ref(false)
  let mediaStream = null
  let mediaRecorder = null
  let chunks = []

  async function start() {
    if (recording.value) return
    chunks = []
    mediaStream = await navigator.mediaDevices.getUserMedia({ audio: true })
    const mime = MediaRecorder.isTypeSupported('audio/webm;codecs=opus')
      ? 'audio/webm;codecs=opus'
      : (MediaRecorder.isTypeSupported('audio/webm') ? 'audio/webm' : '')
    mediaRecorder = mime
      ? new MediaRecorder(mediaStream, { mimeType: mime })
      : new MediaRecorder(mediaStream)
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
        stopTracks()
        if (!chunks.length) {
          resolve(null)
          return
        }
        const type = recorder.mimeType || 'audio/webm'
        resolve(new Blob(chunks, { type }))
      }
      recorder.onerror = () => {
        recording.value = false
        stopTracks()
        reject(new Error('录音失败'))
      }
      try {
        recorder.stop()
      } catch (err) {
        recording.value = false
        stopTracks()
        reject(err)
      }
    })
  }

  function cancel() {
    if (mediaRecorder && recording.value) {
      try { mediaRecorder.stop() } catch { /* ignore */ }
    }
    chunks = []
    recording.value = false
    stopTracks()
  }

  return { recording, start, stop, cancel }
}
