/**
 * Spawn / stop the WeChat ClawBot host process and speak the JSON-line protocol.
 */
import { spawn } from 'node:child_process'
import fs from 'node:fs'
import path from 'node:path'
import readline from 'node:readline'
import { app } from 'electron'
import { HOST_CMD, HOST_MSG, encodeHostCommand, parseHostLine } from './wechatProtocol.js'
import { getWechatChannelRoot, resolveInstalledWechatChannel } from './wechatChannelRelease.js'

function credPath() {
  return path.join(getWechatChannelRoot(), 'credentials.json')
}

function statePath() {
  return path.join(getWechatChannelRoot(), 'sync-state.json')
}

export function createWechatHost({ log = () => {}, onMessage = () => {} } = {}) {
  let proc = null
  let state = 'stopped'
  let lastError = ''
  let qr = { dataUrl: '', text: '' }
  let loggedIn = false

  function setState(next, extra = {}) {
    state = next
    if (extra.error != null) lastError = String(extra.error)
  }

  function send(cmd) {
    if (!proc?.stdin?.writable) return false
    proc.stdin.write(encodeHostCommand(cmd))
    return true
  }

  function handleLine(line) {
    const msg = parseHostLine(line)
    if (!msg) return
    if (msg.type === HOST_MSG.QR) {
      qr = { dataUrl: msg.dataUrl || '', text: msg.text || '' }
    }
    if (msg.type === HOST_MSG.LOGGED_IN) {
      loggedIn = true
      qr = { dataUrl: '', text: '' }
      send({ type: HOST_CMD.START_POLL })
    }
    if (msg.type === HOST_MSG.ERROR) {
      lastError = String(msg.message || 'host error')
    }
    onMessage(msg)
  }

  async function start() {
    if (proc) return getStatus()
    const launch = resolveInstalledWechatChannel()
    if (!launch?.nodePath || !launch?.hostPath) {
      lastError = 'runtime_not_installed'
      setState('error')
      throw new Error('wechat channel runtime not installed')
    }
    lastError = ''
    loggedIn = false
    qr = { dataUrl: '', text: '' }
    setState('starting')
    fs.mkdirSync(getWechatChannelRoot(), { recursive: true })
    proc = spawn(launch.nodePath, [launch.hostPath, '--cred', credPath(), '--state', statePath()], {
      cwd: launch.cwd,
      env: {
        ...process.env,
        LIANYU_BOT_AGENT: `LianYu/${app.getVersion?.() || '0.0.0'}`,
      },
      stdio: ['pipe', 'pipe', 'pipe'],
      windowsHide: true,
    })
    const rl = readline.createInterface({ input: proc.stdout })
    rl.on('line', handleLine)
    proc.stderr.on('data', (buf) => {
      const s = String(buf || '').trim()
      if (s) log(`[wechatHost] ${s.slice(0, 200)}`)
    })
    proc.on('exit', (code) => {
      log(`[wechatHost] exit ${code}`)
      proc = null
      loggedIn = false
      if (state !== 'stopped') setState('error', { error: `host_exit_${code}` })
    })
    setState('running')
    const hasCred = (() => {
      try {
        const raw = JSON.parse(fs.readFileSync(credPath(), 'utf8'))
        return Boolean(raw?.botToken)
      } catch {
        return false
      }
    })()
    if (hasCred) {
      send({ type: HOST_CMD.START_POLL })
      loggedIn = true
    }
    return getStatus()
  }

  function requestLogin() {
    qr = { dataUrl: '', text: '' }
    loggedIn = false
    return send({ type: HOST_CMD.LOGIN })
  }

  function sendText({ toUserId, contextToken, text }) {
    return send({
      type: HOST_CMD.SEND_TEXT,
      toUserId,
      contextToken,
      text,
    })
  }

  async function stop() {
    setState('stopped')
    if (!proc) return getStatus()
    send({ type: HOST_CMD.STOP })
    const child = proc
    proc = null
    loggedIn = false
    await new Promise((resolve) => {
      const t = setTimeout(() => {
        try { child.kill() } catch { /* ignore */ }
        resolve()
      }, 3000)
      child.once('exit', () => {
        clearTimeout(t)
        resolve()
      })
    })
    return getStatus()
  }

  function getStatus() {
    return {
      state,
      lastError,
      loggedIn,
      qrDataUrl: qr.dataUrl,
      qrText: qr.text,
      running: Boolean(proc),
    }
  }

  return { start, stop, requestLogin, sendText, getStatus }
}
